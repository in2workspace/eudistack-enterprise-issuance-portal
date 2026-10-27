package es.in2.issuanceportal.shared.tenant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * The tenants that have a schema of this service, active or not. A tenant deactivated with journeys in
 * progress still holds their personal data, so expiry and migrations go by the schemas that exist, not
 * by the tenants that are active now.
 */
@Component
public class TenantSchemas {

    private final JdbcClient jdbc;

    public TenantSchemas(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<TenantId> withSchema() {
        return jdbc.sql("SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE :pattern "
                        + "ORDER BY schema_name")
                .param("pattern", "%" + TenantId.SCHEMA_SUFFIX.replace("_", "\\_"))
                .query(String.class)
                .list()
                .stream()
                .map(schema -> schema.substring(0, schema.length() - TenantId.SCHEMA_SUFFIX.length()))
                .map(TenantId::parse)
                .flatMap(Optional::stream)
                .toList();
    }
}
