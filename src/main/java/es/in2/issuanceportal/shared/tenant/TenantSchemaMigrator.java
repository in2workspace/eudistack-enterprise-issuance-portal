package es.in2.issuanceportal.shared.tenant;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

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

    public TenantSchemaMigrator(DataSource dataSource, TenantRegistry registry) {
        this.dataSource = dataSource;
        this.registry = registry;
    }

    @Override
    public void afterPropertiesSet() {
        registry.activeTenants().forEach(this::migrate);
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
