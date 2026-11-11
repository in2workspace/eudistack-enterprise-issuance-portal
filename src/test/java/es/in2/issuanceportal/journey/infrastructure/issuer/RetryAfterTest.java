package es.in2.issuanceportal.journey.infrastructure.issuer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RetryAfterTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    @Test
    void parse_seconds_isThatWait() {
        assertThat(RetryAfter.parse("7", NOW)).contains(Duration.ofSeconds(7));
    }

    @Test
    void parse_httpDate_isTheTimeLeftUntilIt() {
        assertThat(RetryAfter.parse("Thu, 05 Nov 2026 10:00:12 GMT", NOW)).contains(Duration.ofSeconds(12));
    }

    @Test
    void parse_httpDateInThePast_isNoWait() {
        assertThat(RetryAfter.parse("Thu, 05 Nov 2026 09:00:00 GMT", NOW)).contains(Duration.ZERO);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "soon", "-3", "1.5", "99999999999999999999999"})
    void parse_notValid_isEmpty(String header) {
        assertThat(RetryAfter.parse(header, NOW)).isEmpty();
    }

    @Test
    void parse_absent_isEmpty() {
        assertThat(RetryAfter.parse(null, NOW)).isEmpty();
    }
}
