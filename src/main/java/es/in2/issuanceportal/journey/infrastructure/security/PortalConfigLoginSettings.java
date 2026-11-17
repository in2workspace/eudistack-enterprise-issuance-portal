package es.in2.issuanceportal.journey.infrastructure.security;

import es.in2.issuanceportal.journey.application.PortalConfigService;
import es.in2.issuanceportal.journey.domain.config.PortalConfig;
import es.in2.issuanceportal.journey.domain.config.ServiceSettings;
import es.in2.issuanceportal.shared.security.LoginSettings;
import es.in2.issuanceportal.shared.security.TenantLoginSettings;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.springframework.stereotype.Component;

/**
 * The tenant's {@code portal_client_id}, {@code identifier_types} and the {@code acr} values of its
 * {@code assurance_level}, read from its cached portal configuration.
 */
@Component
class PortalConfigLoginSettings implements TenantLoginSettings {

    private final PortalConfigService configs;

    PortalConfigLoginSettings(PortalConfigService configs) {
        this.configs = configs;
    }

    @Override
    public LoginSettings of(TenantId tenant) {
        PortalConfig config = configs.current(tenant);
        ServiceSettings settings = config.settings();
        return new LoginSettings(settings.portalClientId(), settings.identifierTypes(),
                config.assuranceLevel().acceptedAcrValues());
    }
}
