package es.in2.issuanceportal.journey.infrastructure.mail;

import es.in2.issuanceportal.journey.application.port.IncidentMailer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.function.Supplier;

@Slf4j
@Configuration
class IncidentMailConfiguration {

    /** The sender of every message: the {@code mail.smtp.from} of {@code spring.mail}, as in the wallet. */
    static final String FROM_PROPERTY = "${spring.mail.properties.mail.smtp.from:}";

    @Bean
    IncidentMailer incidentMailer(MailProperties properties, ObjectProvider<JavaMailSender> mailSender,
                                  @Value(FROM_PROPERTY) String from) {
        log.info("Incidents sent with the {} mail provider", properties.provider());
        return mailerFor(properties, mailSender::getObject, from);
    }

    static IncidentMailer mailerFor(MailProperties properties, Supplier<JavaMailSender> mailSender, String from) {
        return switch (properties.provider()) {
            case SMTP -> new SmtpIncidentMailer(new SmtpMailSender(mailSender.get(), requireFrom(from)));
            case LOCAL -> new LocalIncidentMailer();
        };
    }

    /** Fails the start, not the first send: an SMTP service without a sender cannot send anything. */
    static String requireFrom(String from) {
        if (from == null || from.isBlank()) {
            throw new IllegalStateException("SMTP_FROM is required with the smtp mail provider");
        }
        return from;
    }
}
