package es.in2.issuanceportal.shared.tenant;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Creates and migrates the schema of every active tenant at startup, before the web server takes
 * requests. A tenant activated while the service runs gets its schema on its first request
 * ({@link #ensureSchema}), so onboarding a tenant needs no restart or deployment (EUD-276 AC-08).
 */
@Slf4j
@Component
public class TenantSchemaMigrator implements InitializingBean {

    static final String LOCATION = "classpath:db/tenant";
    /** How long a failed runtime migration answers at once instead of being retried on every request. */
    static final Duration FAILURE_BACKOFF = Duration.ofSeconds(30);

    private final DataSource dataSource;
    private final TenantRegistry registry;
    private final TenantSchemas schemas;
    private final Set<TenantId> migrated = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<TenantId, Object> locks = new ConcurrentHashMap<>();
    private final Cache<TenantId, Boolean> recentFailures = Caffeine.newBuilder()
            .expireAfterWrite(FAILURE_BACKOFF)
            .maximumSize(1_000)
            .build();

    public TenantSchemaMigrator(DataSource dataSource, TenantRegistry registry, TenantSchemas schemas) {
        this.dataSource = dataSource;
        this.registry = registry;
        this.schemas = schemas;
    }

    /**
     * Active tenants get their schema; schemas of tenants deactivated since are migrated too, so the
     * expiry sweep, which still runs on them, always finds the current tables.
     */
    @Override
    public void afterPropertiesSet() {
        Set<TenantId> tenants = new LinkedHashSet<>(registry.activeTenants());
        tenants.addAll(schemas.withSchema());
        tenants.forEach(this::migrate);
    }

    /**
     * Migrates the tenant's schema the first time this instance sees it. Requests for the same new tenant
     * wait for one migration, other tenants never wait for it, and Flyway's PostgreSQL lock keeps two
     * instances from migrating it at once. A failed migration is not retried for {@link #FAILURE_BACKOFF}:
     * meanwhile its requests fail at once, so a failing tenant cannot pile up requests or Flyway runs.
     */
    public void ensureSchema(TenantId tenant) {
        if (migrated.contains(tenant)) {
            return;
        }
        synchronized (locks.computeIfAbsent(tenant, key -> new Object())) {
            if (migrated.contains(tenant)) {
                return;
            }
            if (recentFailures.getIfPresent(tenant) != null) {
                throw new FlywayException("Schema of tenant " + tenant + " failed to migrate moments ago");
            }
            try {
                migrate(tenant);
            } catch (RuntimeException e) {
                recentFailures.put(tenant, Boolean.TRUE);
                throw e;
            }
        }
    }

    void migrate(TenantId tenant) {
        var result = Flyway.configure()
                .dataSource(dataSource)
                .schemas(tenant.schemaName())
                .createSchemas(true)
                .locations(LOCATION)
                .load()
                .migrate();
        markMigrated(tenant);
        log.info("Tenant schema {} at version {}", tenant.schemaName(), result.targetSchemaVersion);
    }

    void markMigrated(TenantId tenant) {
        migrated.add(tenant);
    }
}
