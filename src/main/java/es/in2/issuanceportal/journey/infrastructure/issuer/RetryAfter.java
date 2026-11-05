package es.in2.issuanceportal.journey.infrastructure.issuer;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/** The {@code Retry-After} header (RFC 9110 §10.2.3): a number of seconds or an HTTP date. */
final class RetryAfter {

    private RetryAfter() {
    }

    /** The wait the header asks for, or empty when there is none or it is not valid. */
    static Optional<Duration> parse(String header, Instant now) {
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        String value = header.trim();
        if (value.chars().allMatch(Character::isDigit)) {
            try {
                return Optional.of(Duration.ofSeconds(Long.parseLong(value)));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        try {
            Instant date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration wait = Duration.between(now, date);
            return Optional.of(wait.isNegative() ? Duration.ZERO : wait);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
