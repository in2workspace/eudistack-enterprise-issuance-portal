package es.in2.issuanceportal.shared.tenant;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Reads {@code public.tenant_registry}, owned by the platform. Each tenant's status is cached for a
 * minute so the check on every request does not cost a query.
 */
@Component
public class JdbcTenantRegistry implements TenantRegistry {

    private static final String ACTIVE = "active";

    private final JdbcClient jdbc;
    private final Cache<TenantId, Boolean> activeByTenant = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(1_000)
            .build();

    public JdbcTenantRegistry(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isActive(TenantId tenant) {
        return activeByTenant.get(tenant, this::queryIsActive);
    }

    @Override
    public List<TenantId> activeTenants() {
        return jdbc.sql("SELECT schema_name FROM public.tenant_registry WHERE status = :status ORDER BY schema_name")
                .param("status", ACTIVE)
                .query(String.class)
                .list()
                .stream()
                .map(TenantId::parse)
                .flatMap(Optional::stream)
                .toList();
    }

    private boolean queryIsActive(TenantId tenant) {
        return jdbc.sql("SELECT count(*) FROM public.tenant_registry WHERE schema_name = :tenant AND status = :status")
                .param("tenant", tenant.value())
                .param("status", ACTIVE)
                .query(Long.class)
                .single() > 0;
    }
}
