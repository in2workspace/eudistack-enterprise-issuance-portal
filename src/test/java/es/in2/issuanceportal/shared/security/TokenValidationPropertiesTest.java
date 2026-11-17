package es.in2.issuanceportal.shared.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenValidationPropertiesTest {

    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);
    private static final Duration FIVE_SECONDS = Duration.ofSeconds(5);

    @Test
    void constructor_absoluteUrlAndZeroReloadInterval_isAccepted() {
        assertThatCode(() -> new TokenValidationProperties("http://verifier:8082/verifier", TEN_MINUTES,
                Duration.ZERO, FIVE_SECONDS)).doesNotThrowAnyException();
    }

    @Test
    void constructor_urlWithoutHost_isRejected() {
        assertThatThrownBy(() -> new TokenValidationProperties("/verifier", TEN_MINUTES, FIVE_SECONDS, FIVE_SECONDS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("absolute URL");
    }

    @Test
    void constructor_cacheTtlNotPositive_isRejected() {
        assertThatThrownBy(() -> new TokenValidationProperties("http://verifier:8082/verifier", Duration.ZERO,
                FIVE_SECONDS, FIVE_SECONDS)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_negativeReloadInterval_isRejected() {
        assertThatThrownBy(() -> new TokenValidationProperties("http://verifier:8082/verifier", TEN_MINUTES,
                Duration.ofSeconds(-1), FIVE_SECONDS)).isInstanceOf(IllegalArgumentException.class);
    }
}
