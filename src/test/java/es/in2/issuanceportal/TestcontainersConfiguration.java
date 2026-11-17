package es.in2.issuanceportal;

import es.in2.issuanceportal.shared.security.VerifierTokensConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A PostgreSQL with the platform's tenant registry, as {@code init-databases.sh} leaves it locally, and the simulated
 * Verifier whose tokens the service validates ({@link VerifierTokensConfiguration}).
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(VerifierTokensConfiguration.class)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:17-alpine")
                .withInitScript("db/tenant-registry.sql");
    }
}
