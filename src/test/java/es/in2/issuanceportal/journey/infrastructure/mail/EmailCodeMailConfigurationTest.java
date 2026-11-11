package es.in2.issuanceportal.journey.infrastructure.mail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import es.in2.issuanceportal.journey.application.port.EmailCodeMessage;
import es.in2.issuanceportal.journey.domain.EmailLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class EmailCodeMailConfigurationTest {

    private static final String FROM = "noreply@mail.stg.eudistack.net";
    private static final EmailBrandingProperties BRANDING =
            new EmailBrandingProperties("https://assets.example.org/tenants", Map.of());

    @Test
    void mailerFor_smtpProvider_returnsSmtpMailer() {
        MailProperties properties = new MailProperties(MailProperties.Provider.SMTP);

        assertThat(EmailCodeMailConfiguration.mailerFor(properties, () -> mock(JavaMailSender.class), FROM, BRANDING))
                .isInstanceOf(SmtpEmailCodeMailer.class);
    }

    @Test
    void mailerFor_localProvider_returnsLocalMailerWithoutNeedingASender() {
        MailProperties properties = new MailProperties(MailProperties.Provider.LOCAL);

        assertThat(EmailCodeMailConfiguration.mailerFor(properties, () -> {
            throw new AssertionError("No mail sender in local");
        }, null, new EmailBrandingProperties(null, Map.of()))).isInstanceOf(LocalEmailCodeMailer.class);
    }

    @Test
    void mailerFor_smtpWithoutSender_failsToStart() {
        MailProperties properties = new MailProperties(MailProperties.Provider.SMTP);

        assertThatThrownBy(() -> EmailCodeMailConfiguration.mailerFor(properties, () -> mock(JavaMailSender.class), "",
                BRANDING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMTP_FROM");
    }

    @Test
    void mailerFor_smtpWithoutAssetsBaseUrl_failsToStart() {
        MailProperties properties = new MailProperties(MailProperties.Provider.SMTP);

        assertThatThrownBy(() -> EmailCodeMailConfiguration.mailerFor(properties, () -> mock(JavaMailSender.class),
                FROM, new EmailBrandingProperties("", Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EMAIL_ASSETS_BASE_URL");
    }

    @Test
    @DisplayName("AC-10 — send_localMailer_logsThatItSentNothingAndNeverTheCodeOrTheAddress")
    void send_localMailer_logsThatItSentNothingAndNeverTheCodeOrTheAddress() {
        Logger logger = (Logger) LoggerFactory.getLogger(LocalEmailCodeMailer.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            new LocalEmailCodeMailer().send(EmailCodeMessage.of("ana@example.org", "042042", "DoctorID",
                    EmailLanguage.ES, "cgcom"));
        } finally {
            logger.detachAppender(logs);
        }

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).isEqualTo("email code not sent: local mail provider");
            assertThat(event.getArgumentArray()).isNull();
        });
    }
}
