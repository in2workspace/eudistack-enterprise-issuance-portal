package es.in2.issuanceportal.journey.infrastructure.issuer;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IssuerApiPropertiesTest {

    private static final String ISSUER = "http://issuer-service:8080/issuer";
    private static final Duration TEN_SECONDS = Duration.ofSeconds(10);
    private static final Duration ONE_SECOND = Duration.ofSeconds(1);
    private static final Duration THIRTY_SECONDS = Duration.ofSeconds(30);

    @Test
    void new_absoluteUrl_isValid() {
        assertThat(new IssuerApiProperties(ISSUER, Duration.ofSeconds(10), Duration.ofSeconds(1), false, 20, 50, 10, Duration.ofSeconds(30))
                .issuerInternalUrl()).isEqualTo(ISSUER);
    }

    @Test
    void new_urlNotAbsolute_isRejected() {
        assertThatThrownBy(() -> new IssuerApiProperties("/issuer", TEN_SECONDS, ONE_SECOND, false, 20, 50, 10, THIRTY_SECONDS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void new_zeroTimeout_isRejected() {
        assertThatThrownBy(() -> new IssuerApiProperties(ISSUER, Duration.ZERO, ONE_SECOND, false, 20, 50, 10, THIRTY_SECONDS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void new_negativeInitialWait_isRejected() {
        Duration negativeWait = Duration.ofSeconds(-1);

        assertThatThrownBy(() -> new IssuerApiProperties(ISSUER, TEN_SECONDS, negativeWait, false, 20, 50, 10, THIRTY_SECONDS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void new_noConcurrentCallsAllowed_isRejected() {
        assertThatThrownBy(() -> new IssuerApiProperties(ISSUER, TEN_SECONDS, ONE_SECOND, false,
                0, 50, 10, THIRTY_SECONDS)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void new_failureRateOutOfRange_isRejected() {
        assertThatThrownBy(() -> new IssuerApiProperties(ISSUER, TEN_SECONDS, ONE_SECOND, false,
                20, 101, 10, THIRTY_SECONDS)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void new_noMinimumCalls_isRejected() {
        assertThatThrownBy(() -> new IssuerApiProperties(ISSUER, TEN_SECONDS, ONE_SECOND, false,
                20, 50, 0, THIRTY_SECONDS)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void new_zeroWaitInOpenState_isRejected() {
        assertThatThrownBy(() -> new IssuerApiProperties(ISSUER, TEN_SECONDS, ONE_SECOND, false,
                20, 50, 10, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
