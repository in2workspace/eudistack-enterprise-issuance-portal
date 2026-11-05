package es.in2.issuanceportal.journey.application.port;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An issuance the Issuer accepted. The offer address is a bearer secret: {@link #toString()} never shows it.
 *
 * @param processId          the issuance process, from the {@code Location} of the response
 * @param credentialOfferUri the credential offer for the wallet
 * @param offerExpiresAt     when the offer stops being valid
 */
public record IssuanceResult(UUID processId, String credentialOfferUri, Instant offerExpiresAt) {

    public IssuanceResult {
        Objects.requireNonNull(processId);
        Objects.requireNonNull(credentialOfferUri);
        Objects.requireNonNull(offerExpiresAt);
    }

    @Override
    public String toString() {
        return "IssuanceResult[processId=" + processId + ", offerExpiresAt=" + offerExpiresAt + "]";
    }
}
