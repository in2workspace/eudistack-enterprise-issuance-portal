package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import es.in2.issuanceportal.journey.application.port.SystemTokenProvider;
import es.in2.issuanceportal.shared.tenant.PublicHostProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;

@Slf4j
@Configuration
class SystemTokenConfiguration {

    @Bean
    SystemTokenProvider systemTokenProvider(SystemTokenProperties properties, PublicHostProperties publicHosts,
                                            Clock clock) {
        log.info("System token requested from {} with the {} machine credential provider",
                properties.verifierInternalUrl(), properties.provider());
        return providerFor(properties, publicHosts, clock, () -> SsmClient.builder()
                // Region and credentials come from the ECS task environment (AWS_REGION, the task role).
                .region(new DefaultAwsRegionProviderChain().getRegion())
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .overrideConfiguration(config -> config.apiCallTimeout(properties.requestTimeout()))
                .build());
    }

    static SystemTokenProvider providerFor(SystemTokenProperties properties, PublicHostProperties publicHosts,
                                           Clock clock, Supplier<SsmClient> ssm) {
        MachineCredentialSource source = switch (properties.provider()) {
            case SSM -> new SsmMachineCredentialSource(ssm.get(), properties.parameterTemplate());
            case FILE -> new FileMachineCredentialSource(properties.directory());
        };
        TokenGrant grant = new VerifierTokenGrant(source, new ClientAssertionFactory(),
                restClient(properties.requestTimeout()), clock,
                properties.verifierInternalUrl(), publicHosts);
        return new CachingSystemTokenProvider(grant, clock);
    }

    private static RestClient restClient(Duration timeout) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requests.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(requests).build();
    }
}
