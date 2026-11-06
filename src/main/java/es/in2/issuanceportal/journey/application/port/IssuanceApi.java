package es.in2.issuanceportal.journey.application.port;

import es.in2.issuanceportal.shared.tenant.TenantId;

import java.util.UUID;

/**
 * The tenant's issuance API (design.md §3.3, AD-6): the portal asks it, as a system, for the attestation of
 * the holder and gets the credential offer back.
 */
public interface IssuanceApi {

    /**
     * Requests an issuance. Every attempt, including the retries after a network error, carries the same
     * idempotency key, and always the system token of the tenant, never the holder's.
     *
     * @throws IssuanceRejectedException       when the Issuer refuses the request or answers without a process or an offer
     * @throws IssuanceUnavailableException    when the Issuer does not answer, also after the retries
     * @throws SystemTokenUnavailableException when the tenant has no system token
     */
    IssuanceResult issue(TenantId tenant, UUID idempotencyKey, IssuanceRequest request);
}
