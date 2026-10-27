package es.in2.issuanceportal.journey.application;

import es.in2.issuanceportal.journey.application.port.PortalConfigRepository;
import es.in2.issuanceportal.journey.domain.config.PortalConfig;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.springframework.stereotype.Service;

/** The tenant's portal configuration, for {@code GET /config} and the steps that depend on it. */
@Service
public class PortalConfigService {

    private final PortalConfigRepository configs;

    public PortalConfigService(PortalConfigRepository configs) {
        this.configs = configs;
    }

    /** A tenant without a valid configuration answers {@code config_unavailable} and affects no other tenant. */
    public PortalConfig current(TenantId tenant) {
        return configs.find(tenant).orElseThrow(() -> new ProblemException(ProblemCode.CONFIG_UNAVAILABLE));
    }
}
