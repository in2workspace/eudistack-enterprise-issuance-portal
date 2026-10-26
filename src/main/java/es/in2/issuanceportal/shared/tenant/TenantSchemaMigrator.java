package es.in2.issuanceportal.shared.tenant;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Creates and migrates the schema of every active tenant at startup, before the web server takes
 * requests. A tenant registered while the service runs gets its schema on the next start.
 */
@Slf4j
@Component
public class TenantSchemaMigrator implements InitializingBean {

    static final String LOCATION = "classpath:db/tenant";

    private final DataSource dataSource;
    private final TenantRegistry registry;
    private final TenantSchemas schemas;

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

    void migrate(TenantId tenant) {
        var result = Flyway.configure()
                .dataSource(dataSource)
                .schemas(tenant.schemaName())
                .createSchemas(true)
                .locations(LOCATION)
                .load()
                .migrate();
        log.info("Tenant schema {} at version {}", tenant.schemaName(), result.targetSchemaVersion);
    }
}
