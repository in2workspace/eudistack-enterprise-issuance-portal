package es.in2.issuanceportal.journey.infrastructure.mail;

import es.in2.issuanceportal.journey.application.port.IncidentMailer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.SesV2ClientBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.function.Supplier;

@Slf4j
@Configuration
class IncidentMailConfiguration {

    /** The holder waits for the answer: SES gets this long per call before the incident counts as failed. */
    static final Duration SES_CALL_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    IncidentMailer incidentMailer(IncidentMailProperties properties) {
        log.info("Incidents sent with the {} mail provider", properties.provider());
        return mailerFor(properties, IncidentMailConfiguration::sesClient);
    }

    /** Region and credentials come from the ECS task environment (AWS_REGION, the task role). */
    static SesV2Client sesClient() {
        return sesClient(new DefaultAwsRegionProviderChain().getRegion(), DefaultCredentialsProvider.builder().build(),
                null);
    }

    /**
     * The client as the service uses it, for any mail it sends, with the call limit of
     * {@link #SES_CALL_TIMEOUT}.
     *
     * @param endpoint another address than AWS's, for tests; {@code null} in the service
     */
    static SesV2Client sesClient(Region region, AwsCredentialsProvider credentials, URI endpoint) {
        SesV2ClientBuilder builder = SesV2Client.builder()
                .region(region)
                .credentialsProvider(credentials)
                .overrideConfiguration(config -> config.apiCallTimeout(SES_CALL_TIMEOUT));
        if (endpoint != null) {
            builder.endpointOverride(endpoint);
        }
        return builder.build();
    }

    static IncidentMailer mailerFor(IncidentMailProperties properties, Supplier<SesV2Client> ses) {
        return switch (properties.provider()) {
            case SES -> new SesIncidentMailer(ses.get(), properties.from());
            case LOCAL -> new LocalIncidentMailer();
        };
    }
}
