package es.in2.issuanceportal.journey.infrastructure.mail;

import es.in2.issuanceportal.journey.application.port.IncidentMailer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sesv2.SesV2Client;

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
        return mailerFor(properties, () -> SesV2Client.builder()
                // Region and credentials come from the ECS task environment.
                .overrideConfiguration(config -> config.apiCallTimeout(SES_CALL_TIMEOUT))
                .build());
    }

    static IncidentMailer mailerFor(IncidentMailProperties properties, Supplier<SesV2Client> ses) {
        return switch (properties.provider()) {
            case SES -> new SesIncidentMailer(ses.get(), properties.from());
            case LOCAL -> new LocalIncidentMailer();
        };
    }
}
