package es.in2.issuanceportal.journey.application.port;

import java.time.Instant;
import java.util.Objects;

/**
 * An access token and the moment it expires. It is a secret: {@link #toString()} never shows it.
 */
public record SystemToken(String value, Instant expiresAt) {

    public SystemToken {
        Objects.requireNonNull(value);
        Objects.requireNonNull(expiresAt);
    }

    @Override
    public String toString() {
        return "SystemToken[expiresAt=" + expiresAt + "]";
    }
}
