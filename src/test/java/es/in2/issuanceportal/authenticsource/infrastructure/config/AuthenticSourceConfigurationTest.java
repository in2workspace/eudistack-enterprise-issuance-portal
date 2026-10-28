package es.in2.issuanceportal.authenticsource.infrastructure.config;

import es.in2.issuanceportal.authenticsource.StubAdapter;
import es.in2.issuanceportal.authenticsource.application.port.AuthenticSourceAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticSourceConfigurationTest {

    private static final List<AuthenticSourceAdapter> ADAPTERS =
            List.of(StubAdapter.searchingByBoth("simulated"), StubAdapter.searchingByBoth("cgcom"));

    @Test
    void registry_production_leavesTheSimulatedAdapterOut() {
        assertThat(AuthenticSourceConfiguration.registry("pro", ADAPTERS).types()).containsExactly("cgcom");
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "test", "stg"})
    void registry_otherEnvironments_keepEveryAdapter(String env) {
        assertThat(AuthenticSourceConfiguration.registry(env, ADAPTERS).types())
                .containsExactlyInAnyOrder("simulated", "cgcom");
    }

    @Test
    void registry_noAdapters_isEmpty() {
        assertThat(AuthenticSourceConfiguration.registry("stg", List.of()).types()).isEmpty();
    }
}
