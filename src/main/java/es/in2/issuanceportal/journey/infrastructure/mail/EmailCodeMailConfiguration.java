package es.in2.issuanceportal.journey.infrastructure.mail;

import es.in2.issuanceportal.journey.application.port.EmailCodeMailer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.function.Supplier;

/** The code email goes out the way the incidents do: the same provider switch, SMTP server and sender. */
@Slf4j
@Configuration
class EmailCodeMailConfiguration {

    @Bean
    EmailCodeMailer emailCodeMailer(MailProperties properties, ObjectProvider<JavaMailSender> mailSender,
                                    @Value(IncidentMailConfiguration.FROM_PROPERTY) String from) {
        log.info("Verification codes sent with the {} mail provider", properties.provider());
        return mailerFor(properties, mailSender::getObject, from);
    }

    static EmailCodeMailer mailerFor(MailProperties properties, Supplier<JavaMailSender> mailSender, String from) {
        return switch (properties.provider()) {
            case SMTP -> new SmtpEmailCodeMailer(new SmtpMailSender(mailSender.get(),
                    IncidentMailConfiguration.requireFrom(from)));
            case LOCAL -> new LocalEmailCodeMailer();
        };
    }
}
