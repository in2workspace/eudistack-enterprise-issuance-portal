package es.in2.issuanceportal.journey.application.port;

import es.in2.issuanceportal.shared.tenant.TenantId;

import java.time.Duration;

/**
 * {@code POST /onboarding-tickets} of the tenant's Wallet Unit Service (design.md §3.4): a pre-registration
 * for a holder the portal has identified, with the credential offer the wallet resumes. Called as a system,
 * never with the holder's token, and not retried.
 */
public interface OnboardingTickets {

    /**
     * @param walletUnitServiceUrl the tenant's Wallet Unit Service, as configured in {@code onboarding}
     * @param credentialOffer      the offer exactly as the Issuer returned it
     * @param expiresIn            how long the ticket may last; the service may give a shorter one
     * @throws OnboardingTicketException when the ticket was not created, whatever the reason
     */
    OnboardingTicket request(TenantId tenant, String walletUnitServiceUrl, String email, String credentialOffer,
                             Duration expiresIn);
}
