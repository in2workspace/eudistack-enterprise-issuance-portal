package es.in2.issuanceportal.authenticsource.infrastructure.simulated;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.issuanceportal.authenticsource.application.AdapterRegistry;
import es.in2.issuanceportal.authenticsource.application.port.AuthenticSourceConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Starts the module's adapters as the service does and checks which ones the registry keeps (AC-12). */
class SimulatedAdapterStartupTest {

    @Configuration
    @ComponentScan(basePackages = "es.in2.issuanceportal.authenticsource.infrastructure",
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = {
                ".*\\.simulated\\.SimulatedAuthenticSourceAdapter",
                ".*\\.config\\.AuthenticSourceConfiguration"}))
    static class AdaptersOnly {
    }

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(AdaptersOnly.class)
            .withBean(ObjectMapper.class)
            .withBean(AuthenticSourceConfigRepository.class, () -> tenant -> Optional.empty());

    @Test
    @DisplayName("AC-12 — start_production_registryHasNoSimulatedAdapter")
    void start_production_registryHasNoSimulatedAdapter() {
        context.withPropertyValues("issuance-portal.env=pro").run(started -> {
            assertThat(started).hasNotFailed();
            assertThat(started.getBean(AdapterRegistry.class).find("simulated")).isEmpty();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "test", "stg"})
    @DisplayName("AC-12 — start_otherEnvironments_registryHasTheSimulatedAdapter")
    void start_otherEnvironments_registryHasTheSimulatedAdapter(String env) {
        context.withPropertyValues("issuance-portal.env=" + env).run(started ->
                assertThat(started.getBean(AdapterRegistry.class).find("simulated")).isPresent());
    }
}
