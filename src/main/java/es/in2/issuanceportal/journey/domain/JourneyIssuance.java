package es.in2.issuanceportal.journey.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The issuance process of one request of a journey (design.md §3.7, §5). Permanent and without personal data:
 * it relates the journey to the processes it created. {@code requestId} is the opaque id of the request.
 *
 * @param savedAt when the request was saved, by the holder's declaration or because its offer could no longer be
 *                renewed; {@code null} while it was not
 */
public record JourneyIssuance(UUID journeyId, String requestId, UUID issuanceProcessId, Instant createdAt,
                              Instant savedAt) {

    /** The longest request id the contract admits. */
    public static final int MAX_REQUEST_ID_LENGTH = 64;

    public JourneyIssuance {
        Objects.requireNonNull(journeyId, "journeyId");
        Objects.requireNonNull(issuanceProcessId, "issuanceProcessId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (requestId == null || requestId.isBlank() || requestId.length() > MAX_REQUEST_ID_LENGTH) {
            throw new IllegalArgumentException("requestId");
        }
    }

    public boolean isSaved() {
        return savedAt != null;
    }

    public Optional<Instant> savedAtIfSaved() {
        return Optional.ofNullable(savedAt);
    }
}
