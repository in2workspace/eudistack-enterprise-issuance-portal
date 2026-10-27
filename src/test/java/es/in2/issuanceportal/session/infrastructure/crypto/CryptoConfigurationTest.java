package es.in2.issuanceportal.session.infrastructure.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

class CryptoConfigurationTest {

    private static final Map<String, String> LAPTOP = Map.of();
    private static final Map<String, String> ECS =
            Map.of(CryptoConfiguration.ECS_METADATA, "http://169.254.170.2/v4/abc");

    @ParameterizedTest
    @ValueSource(strings = {"local", "test"})
    void requireLocalKeyAllowed_localKeyInLocalOrTest_starts(String env) {
        assertThatNoException().isThrownBy(() ->
                CryptoConfiguration.requireLocalKeyAllowed(CryptoProperties.Provider.LOCAL, env, LAPTOP));
    }

    @ParameterizedTest
    @ValueSource(strings = {"stg", "pro", "dev", ""})
    void requireLocalKeyAllowed_localKeyInAnyOtherEnvironment_refusesToStart(String env) {
        assertThatIllegalStateException().isThrownBy(() ->
                CryptoConfiguration.requireLocalKeyAllowed(CryptoProperties.Provider.LOCAL, env, LAPTOP));
    }

    @Test
    void requireLocalKeyAllowed_localKeyOnEcsEvenWithEnvLocal_refusesToStart() {
        assertThatIllegalStateException().isThrownBy(() ->
                CryptoConfiguration.requireLocalKeyAllowed(CryptoProperties.Provider.LOCAL, "local", ECS));
    }

    @Test
    void requireLocalKeyAllowed_kmsAnywhere_starts() {
        assertThatNoException().isThrownBy(() ->
                CryptoConfiguration.requireLocalKeyAllowed(CryptoProperties.Provider.KMS, "pro", ECS));
    }
}
