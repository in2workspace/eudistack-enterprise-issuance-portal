package es.in2.issuanceportal.authenticsource.infrastructure.http;

import es.in2.issuanceportal.authenticsource.application.port.SourceSecrets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.time.Clock;
import java.time.Duration;

@Configuration
class HttpSourceConfiguration {

    /** How long a credential read from SSM is kept in memory (design.md AD-4). */
    static final Duration SECRET_TTL = Duration.ofMinutes(5);
    private static final Duration SSM_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    SourceSecrets sourceSecrets(Clock clock) {
        // Region and credentials come from the ECS task environment, as for the machine credential.
        return new SsmSourceSecrets(() -> SsmClient.builder()
                .region(new DefaultAwsRegionProviderChain().getRegion())
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .overrideConfiguration(config -> config.apiCallTimeout(SSM_TIMEOUT))
                .build(), clock, SECRET_TTL);
    }
}
