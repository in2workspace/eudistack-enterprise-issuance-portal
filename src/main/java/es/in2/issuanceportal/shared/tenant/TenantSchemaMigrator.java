package es.in2.issuanceportal.shared.tenant;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates and migrates the schema of every active tenant at startup, before the web server takes
 * requests. A tenant activated while the service runs gets its schema on its first request
 * ({@link #ensureSchema}), so onboarding a tenant needs no restart or deployment (EUD-276 AC-08).
 */
@Slf4j
@Component
public class TenantSchemaMigrator implements InitializingBean {

    static final String LOCATION = "classpath:db/tenant";

    private final DataSource dataSource;
    private final TenantRegistry registry;
    private final TenantSchemas schemas;
    private final Set<TenantId> migrated = ConcurrentHashMap.newKeySet();

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
     * Migrates the tenant's schema the first time this instance sees it. Flyway's PostgreSQL lock keeps
     * two instances that see the same new tenant from migrating it at once.
     */
    public void ensureSchema(TenantId tenant) {
        if (migrated.contains(tenant)) {
            return;
        }
        synchronized (migrated) {
            if (!migrated.contains(tenant)) {
                migrate(tenant);
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
        migrated.add(tenant);
        log.info("Tenant schema {} at version {}", tenant.schemaName(), result.targetSchemaVersion);
    }
}
