package es.in2.issuanceportal.journey.application.port;

import es.in2.issuanceportal.journey.domain.config.PortalConfig;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.util.Optional;

/** Portal configuration of a tenant, loaded at tenant creation and never written by the service. */
public interface PortalConfigRepository {

    /** Empty when the tenant has no configuration or it breaks the contract: both mean not available. */
    Optional<PortalConfig> find(TenantId tenant);
}
