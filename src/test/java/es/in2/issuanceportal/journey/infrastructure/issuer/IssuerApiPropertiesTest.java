package es.in2.issuanceportal.journey.infrastructure.issuer;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IssuerApiPropertiesTest {

    private static final String ISSUER = "http://issuer-service:8080/issuer";

    @Test
    void new_absoluteUrl_isValid() {
        assertThat(new IssuerApiProperties(ISSUER, Duration.ofSeconds(10), Duration.ofSeconds(1), false, 20, 50, 10, Duration.ofSeconds(30))
                .issuerInternalUrl()).isEqualTo(ISSUER);
    }

    @Test
    void new_urlNotAbsolute_isRejected() {
        assertThatThrownBy(() -> new IssuerApiProperties("/issuer", Duration.ofSeconds(10), Duration.ofSeconds(1), false, 20, 50, 10, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void new_zeroTimeout_isRejected() {
        assertThatThrownBy(() -> new IssuerApiProperties(ISSUER, Duration.ZERO, Duration.ofSeconds(1), false, 20, 50, 10, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void new_negativeInitialWait_isRejected() {
        assertThatThrownBy(() -> new IssuerApiProperties(ISSUER, Duration.ofSeconds(10), Duration.ofSeconds(-1), false, 20, 50, 10, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void new_noConcurrentCallsAllowed_isRejected() {
        assertThatThrownBy(() -> new IssuerApiProperties(ISSUER, Duration.ofSeconds(10), Duration.ofSeconds(1), false,
                0, 50, 10, Duration.ofSeconds(30))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void new_failureRateOutOfRange_isRejected() {
        assertThatThrownBy(() -> new IssuerApiProperties(ISSUER, Duration.ofSeconds(10), Duration.ofSeconds(1), false,
                20, 101, 10, Duration.ofSeconds(30))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void new_noMinimumCalls_isRejected() {
        assertThatThrownBy(() -> new IssuerApiProperties(ISSUER, Duration.ofSeconds(10), Duration.ofSeconds(1), false,
                20, 50, 0, Duration.ofSeconds(30))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void new_zeroWaitInOpenState_isRejected() {
        assertThatThrownBy(() -> new IssuerApiProperties(ISSUER, Duration.ofSeconds(10), Duration.ofSeconds(1), false,
                20, 50, 10, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
