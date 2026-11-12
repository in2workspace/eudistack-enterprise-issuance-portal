package es.in2.issuanceportal.journey.infrastructure.security;

import es.in2.issuanceportal.journey.application.PortalConfigService;
import es.in2.issuanceportal.journey.domain.config.ServiceSettings;
import es.in2.issuanceportal.shared.security.LoginSettings;
import es.in2.issuanceportal.shared.security.TenantLoginSettings;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.springframework.stereotype.Component;

/** The tenant's {@code portal_client_id} and {@code identifier_types}, read from its cached portal configuration. */
@Component
class PortalConfigLoginSettings implements TenantLoginSettings {

    private final PortalConfigService configs;

    PortalConfigLoginSettings(PortalConfigService configs) {
        this.configs = configs;
    }

    @Override
    public LoginSettings of(TenantId tenant) {
        ServiceSettings settings = configs.current(tenant).settings();
        return new LoginSettings(settings.portalClientId(), settings.identifierTypes());
    }
}
