package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import es.in2.issuanceportal.journey.application.port.SystemToken;
import es.in2.issuanceportal.shared.tenant.TenantId;

/** Asks the tenant's Verifier for a new system token. */
interface TokenGrant {

    /**
     * @throws es.in2.issuanceportal.journey.application.port.SystemTokenUnavailableException
     *         when there is no credential or the Verifier does not grant a token
     */
    SystemToken request(TenantId tenant);
}
