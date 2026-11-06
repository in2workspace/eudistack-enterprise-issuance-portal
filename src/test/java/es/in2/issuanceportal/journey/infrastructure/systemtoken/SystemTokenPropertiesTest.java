package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import es.in2.issuanceportal.journey.infrastructure.systemtoken.SystemTokenProperties.Provider;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemTokenPropertiesTest {

    private static final String VERIFIER = "http://verifier:8082/verifier";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void new_ssmWithTemplate_isValid() {
        assertThat(new SystemTokenProperties(Provider.SSM, "/eudistack/dev/{tenant}/machine-credential", null,
                VERIFIER, TIMEOUT).provider()).isEqualTo(Provider.SSM);
    }

    @Test
    void new_ssmWithoutTenantInTemplate_isRejected() {
        assertThatThrownBy(() -> new SystemTokenProperties(Provider.SSM, "/eudistack/dev/machine-credential", null,
                VERIFIER, TIMEOUT)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void new_fileWithoutDirectory_isRejected() {
        assertThatThrownBy(() -> new SystemTokenProperties(Provider.FILE, null, Path.of(""), VERIFIER, TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void new_verifierUrlNotAbsolute_isRejected() {
        assertThatThrownBy(() -> new SystemTokenProperties(Provider.FILE, null, Path.of("/run/secrets"), "/verifier",
                TIMEOUT)).isInstanceOf(IllegalArgumentException.class);
    }
}
