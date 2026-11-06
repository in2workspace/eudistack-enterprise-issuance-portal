package es.in2.issuanceportal.journey.application.port;

import java.time.Instant;
import java.util.Objects;

/**
 * An onboarding ticket the Wallet Unit Service created. The address is a bearer secret: {@link #toString()}
 * never shows it.
 *
 * @param onboardingUrl the address the holder opens in the wallet
 * @param expiresAt     when the ticket stops being valid
 */
public record OnboardingTicket(String onboardingUrl, Instant expiresAt) {

    public OnboardingTicket {
        Objects.requireNonNull(onboardingUrl);
        Objects.requireNonNull(expiresAt);
    }

    @Override
    public String toString() {
        return "OnboardingTicket[expiresAt=" + expiresAt + ", onboardingUrl redacted]";
    }
}
