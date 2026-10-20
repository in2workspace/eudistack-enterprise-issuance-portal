package es.in2.issuanceportal.shared.tenant;

import es.in2.issuanceportal.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcTenantRegistryIT {

    @Autowired
    private TenantRegistry registry;

    @Test
    void isActive_activeTenant_returnsTrue() {
        assertThat(registry.isActive(new TenantId("sandbox"))).isTrue();
    }

    @Test
    void isActive_inactiveOrUnknownTenant_returnsFalse() {
        assertThat(registry.isActive(new TenantId("retired"))).isFalse();
        assertThat(registry.isActive(new TenantId("altia"))).isFalse();
    }

    @Test
    void activeTenants_mixedRegistry_listsOnlyActive() {
        assertThat(registry.activeTenants()).containsExactly(new TenantId("kpmg"), new TenantId("sandbox"));
    }
}
