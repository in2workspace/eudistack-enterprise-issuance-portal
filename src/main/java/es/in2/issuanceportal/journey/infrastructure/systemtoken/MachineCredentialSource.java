package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import es.in2.issuanceportal.shared.tenant.TenantId;

import java.util.Optional;

/** Where the machine credential of each tenant is kept. */
interface MachineCredentialSource {

    /**
     * @return the tenant's credential, or empty when there is none or it cannot be used
     * @throws es.in2.issuanceportal.journey.application.port.SystemTokenUnavailableException
     *         with {@code SECRET_STORE_UNAVAILABLE} when the store does not answer
     */
    Optional<MachineIdentity> find(TenantId tenant);
}
