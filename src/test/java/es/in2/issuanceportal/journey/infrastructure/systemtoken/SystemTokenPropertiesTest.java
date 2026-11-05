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
    void new_secretsManagerWithTemplate_isValid() {
        assertThat(new SystemTokenProperties(Provider.SECRETS_MANAGER, "eudistack/{tenant}/portal", null,
                VERIFIER, TIMEOUT).provider()).isEqualTo(Provider.SECRETS_MANAGER);
    }

    @Test
    void new_secretsManagerWithoutTenantInTemplate_isRejected() {
        assertThatThrownBy(() -> new SystemTokenProperties(Provider.SECRETS_MANAGER, "eudistack/portal", null,
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
