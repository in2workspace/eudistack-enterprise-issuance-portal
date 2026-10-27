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
            assertThat(limiter.tryConsume("203.0.113.7")).isEmpty();
        }

        assertThat(limiter.tryConsume("203.0.113.7")).isPresent();
    }

    @Test
    @DisplayName("AC-06 — tryConsume_acrossTheTurnOfTheHour_acceptsOnlyWhatLeftTheWindow")
    void tryConsume_acrossTheTurnOfTheHour_acceptsOnlyWhatLeftTheWindow() {
        // A fixed one-hour window opened at minute 0 would refill all five at minute 60.
        assertThat(limiter.tryConsume("203.0.113.7")).isEmpty();
        clock.advance(Duration.ofMinutes(59));
        for (int i = 0; i < 4; i++) {
            assertThat(limiter.tryConsume("203.0.113.7")).isEmpty();
        }

        clock.advance(Duration.ofMinutes(2));

        assertThat(limiter.tryConsume("203.0.113.7")).isEmpty();
        assertThat(limiter.tryConsume("203.0.113.7")).isPresent();
    }

    @Test
    @DisplayName("AC-06 — tryConsume_oldestLeavesTheWindow_acceptsOneMore")
    void tryConsume_oldestLeavesTheWindow_acceptsOneMore() {
        assertThat(limiter.tryConsume("203.0.113.7")).isEmpty();
        clock.advance(Duration.ofMinutes(30));
        for (int i = 0; i < 4; i++) {
            assertThat(limiter.tryConsume("203.0.113.7")).isEmpty();
        }

        clock.advance(Duration.ofMinutes(30));

        assertThat(limiter.tryConsume("203.0.113.7")).isEmpty();
        assertThat(limiter.tryConsume("203.0.113.7")).isPresent();
    }

    @Test
    void tryConsume_overTheLimit_waitsUntilTheOldestLeavesTheWindow() {
        assertThat(limiter.tryConsume("203.0.113.7")).isEmpty();
        clock.advance(Duration.ofMinutes(20));
        for (int i = 0; i < 4; i++) {
            limiter.tryConsume("203.0.113.7");
        }

        assertThat(limiter.tryConsume("203.0.113.7")).contains(Duration.ofMinutes(40));
        clock.advance(Duration.ofMinutes(40));
        assertThat(limiter.tryConsume("203.0.113.7")).isEmpty();
    }

    @Test
    @DisplayName("EC-04 — tryConsume_otherAddress_isNotAffected")
    void tryConsume_otherAddress_isNotAffected() {
        exhaust("203.0.113.7");

        assertThat(limiter.tryConsume("198.51.100.4")).isEmpty();
    }

    @Test
    void tryConsume_afterTheHour_isAcceptedAgain() {
        exhaust("203.0.113.7");

        clock.advance(Duration.ofHours(1));

        assertThat(limiter.tryConsume("203.0.113.7")).isEmpty();
    }

    @Test
    void tryConsume_addressesInTheSameIpv6Slash64_shareTheLimit() {
        for (int i = 1; i <= 5; i++) {
            assertThat(limiter.tryConsume("2001:db8:1:2::" + i)).isEmpty();
        }

        assertThat(limiter.tryConsume("2001:db8:1:2:ffff:ffff:ffff:ffff")).isPresent();
        assertThat(limiter.tryConsume("2001:db8:1:3::1")).isEmpty();
    }

    @Test
    void clientKey_ipv4MappedAddress_countsAsItsIpv4Address() {
        assertThat(IncidentRateLimiter.clientKey("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(IncidentRateLimiter.clientKey("2001:db8:1:2::5")).isEqualTo("20010db800010002::/64");
    }

    private void exhaust(String ip) {
        for (int i = 0; i < 5; i++) {
            limiter.tryConsume(ip);
        }
        assertThat(limiter.tryConsume(ip)).isPresent();
    }
}
