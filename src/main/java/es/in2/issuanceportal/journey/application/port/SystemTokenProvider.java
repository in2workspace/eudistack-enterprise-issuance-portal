package es.in2.issuanceportal.journey.application.port;

import es.in2.issuanceportal.shared.tenant.TenantId;

/**
 * The access token the portal uses as a system, per tenant, for the Issuer and the Wallet Unit Service
 * (design.md AD-5). A tenant's token is never served for another.
 */
public interface SystemTokenProvider {

    /**
     * A token of the tenant with more than the safety margin left before it expires: the cached one, or a
     * new one from the tenant's Verifier.
     *
     * @throws SystemTokenUnavailableException when the tenant has no machine credential or the Verifier
     *                                         does not grant a token
     */
    SystemToken tokenFor(TenantId tenant);

    /** Forgets the tenant's token, so the next call asks for another (the Issuer or Wallet Unit Service said 401). */
    void invalidate(TenantId tenant);
}
