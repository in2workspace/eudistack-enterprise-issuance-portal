package es.in2.issuanceportal.journey.infrastructure.mail;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class IncidentMailConfigurationTest {

    private static final String FROM = "noreply@mail.stg.eudistack.net";

    @Test
    void mailerFor_smtpProvider_returnsSmtpMailer() {
        MailProperties properties = new MailProperties(MailProperties.Provider.SMTP);

        assertThat(IncidentMailConfiguration.mailerFor(properties, () -> mock(JavaMailSender.class), FROM))
                .isInstanceOf(SmtpIncidentMailer.class);
    }

    @Test
    void mailerFor_localProvider_returnsLocalMailerWithoutNeedingASender() {
        MailProperties properties = new MailProperties(MailProperties.Provider.LOCAL);

        assertThat(IncidentMailConfiguration.mailerFor(properties, () -> {
            throw new AssertionError("No mail sender in local");
        }, null)).isInstanceOf(LocalIncidentMailer.class);
    }

    @Test
    void mailerFor_smtpWithoutSender_failsToStart() {
        MailProperties properties = new MailProperties(MailProperties.Provider.SMTP);

        assertThatThrownBy(() -> IncidentMailConfiguration.mailerFor(properties, () -> mock(JavaMailSender.class), " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMTP_FROM");
    }
}
