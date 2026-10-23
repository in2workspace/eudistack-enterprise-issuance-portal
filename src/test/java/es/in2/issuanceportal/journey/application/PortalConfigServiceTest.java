package es.in2.issuanceportal.journey.application;

import es.in2.issuanceportal.journey.domain.config.LegalLinks;
import es.in2.issuanceportal.journey.domain.config.PortalConfig;
import es.in2.issuanceportal.journey.domain.config.Support;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortalConfigServiceTest {

    private static final TenantId SANDBOX = new TenantId("sandbox");

    @Test
    void current_configuredTenant_returnsItsConfiguration() {
        PortalConfig config = new PortalConfig(Map.of(), new LegalLinks(null, null, null, null),
                new Support(null, null, null, null), "https://sandbox.stg.eudistack.net/wallet", null);
        PortalConfigService service = new PortalConfigService(tenant -> Optional.of(config));

        assertThat(service.current(SANDBOX)).isSameAs(config);
    }

    @Test
    void current_tenantWithoutValidConfiguration_isConfigUnavailable() {
        PortalConfigService service = new PortalConfigService(tenant -> Optional.empty());

        assertThatThrownBy(() -> service.current(SANDBOX))
                .isInstanceOfSatisfying(ProblemException.class,
                        e -> assertThat(e.problem()).isEqualTo(ProblemCode.CONFIG_UNAVAILABLE));
    }
}
