package es.in2.issuanceportal.journey.infrastructure.web;

import es.in2.issuanceportal.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-26T10:00:00Z"));
    private final IncidentRateLimiter limiter = new IncidentRateLimiter(clock);

    @Test
    @DisplayName("AC-06 — tryConsume_sixthWithinAnHour_isRejected")
    void tryConsume_sixthWithinAnHour_isRejected() {
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofMinutes(5));
            assertThat(limiter.tryConsume("203.0.113.7")).isTrue();
        }

        assertThat(limiter.tryConsume("203.0.113.7")).isFalse();
    }

    @Test
    @DisplayName("EC-04 — tryConsume_otherAddress_isNotAffected")
    void tryConsume_otherAddress_isNotAffected() {
        exhaust("203.0.113.7");

        assertThat(limiter.tryConsume("198.51.100.4")).isTrue();
    }

    @Test
    void tryConsume_afterTheHour_isAcceptedAgain() {
        exhaust("203.0.113.7");

        clock.advance(Duration.ofHours(1));

        assertThat(limiter.tryConsume("203.0.113.7")).isTrue();
    }

    private void exhaust(String ip) {
        for (int i = 0; i < 5; i++) {
            limiter.tryConsume(ip);
        }
        assertThat(limiter.tryConsume(ip)).isFalse();
    }
}
