package es.in2.issuanceportal.journey.infrastructure.mail;

import es.in2.issuanceportal.journey.application.port.EmailCodeMailer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import java.util.function.Supplier;

/** The code email goes out the way the incidents do: the same provider switch, sender and call limit. */
@Slf4j
@Configuration
class EmailCodeMailConfiguration {

    @Bean
    EmailCodeMailer emailCodeMailer(IncidentMailProperties properties) {
        log.info("Verification codes sent with the {} mail provider", properties.provider());
        return mailerFor(properties, IncidentMailConfiguration::sesClient);
    }

    static EmailCodeMailer mailerFor(IncidentMailProperties properties, Supplier<SesV2Client> ses) {
        return switch (properties.provider()) {
            case SES -> new SesEmailCodeMailer(ses.get(), properties.from());
            case LOCAL -> new LocalEmailCodeMailer();
        };
    }
}
