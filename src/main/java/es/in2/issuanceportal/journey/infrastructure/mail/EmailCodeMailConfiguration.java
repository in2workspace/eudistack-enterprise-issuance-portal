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
                                    @Value(IncidentMailConfiguration.FROM_PROPERTY) String from,
                                    EmailBrandingProperties branding) {
        log.info("Verification codes sent with the {} mail provider", properties.provider());
        return mailerFor(properties, mailSender::getObject, from, branding);
    }

    static EmailCodeMailer mailerFor(MailProperties properties, Supplier<JavaMailSender> mailSender, String from,
                                     EmailBrandingProperties branding) {
        return switch (properties.provider()) {
            case SMTP -> new SmtpEmailCodeMailer(
                    new SmtpMailSender(mailSender.get(), IncidentMailConfiguration.requireFrom(from)),
                    new EmailCodeHtml(new EmailBrandingResolver(requireAssetsBaseUrl(branding))));
            case LOCAL -> new LocalEmailCodeMailer();
        };
    }

    /** Fails the start, not the first send: the images of a branded tenant would be broken links. */
    static EmailBrandingProperties requireAssetsBaseUrl(EmailBrandingProperties branding) {
        if (branding.assetsBaseUrl() == null || branding.assetsBaseUrl().isBlank()) {
            throw new IllegalStateException("EMAIL_ASSETS_BASE_URL is required with the smtp mail provider");
        }
        return branding;
    }
}
