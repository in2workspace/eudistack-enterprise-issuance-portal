package es.in2.issuanceportal.journey.infrastructure.mail;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import es.in2.issuanceportal.journey.application.port.EmailCodeDeliveryException;
import es.in2.issuanceportal.journey.application.port.EmailCodeMessage;
import es.in2.issuanceportal.journey.domain.EmailLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.IOException;
import java.util.Map;

import static es.in2.issuanceportal.journey.infrastructure.mail.SmtpTestSupport.FROM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The TLS settings of the shipped application.yaml: no mail, and no credentials, without a trusted TLS channel. */
class SmtpTlsHardeningTest {

    private static final EmailCodeMessage MESSAGE = EmailCodeMessage.of("ana@example.org", "042042",
            "DoctorID", EmailLanguage.ES, "cgcom");

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL_TLS = new GreenMailExtension(ServerSetup.SMTPS.dynamicPort())
            .withPerMethodLifecycle(true);

    private static ApplicationContextRunner shippedConfig(int port, String... overrides) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withPropertyValues("spring.mail.host=127.0.0.1", "spring.mail.port=" + port,
                        "spring.mail.username=ses-user", "spring.mail.password=ses-secret",
                        "spring.mail.properties.mail.smtp.from=" + FROM);
        return runner.withPropertyValues(overrides);
    }

    @Test
    @DisplayName("F1 — send_serverWithoutStartTls_failsAndSendsNoCredentialsNorMessage")
    void send_serverWithoutStartTls_failsAndSendsNoCredentialsNorMessage() throws IOException {
        try (SmtpTestSupport.PlainSmtpServer plain = new SmtpTestSupport.PlainSmtpServer()) {
            shippedConfig(plain.port()).run(context -> {
                SmtpEmailCodeMailer mailer = new SmtpEmailCodeMailer(
                        new SmtpMailSender(context.getBean(JavaMailSender.class), FROM), SmtpTestSupport.html());

                assertThatThrownBy(() -> mailer.send(MESSAGE))
                        .isInstanceOf(EmailCodeDeliveryException.class)
                        .hasNoCause();
            });

            assertThat(plain.commands()).isNotEmpty()
                    .noneMatch(command -> command.toUpperCase().startsWith("AUTH"))
                    .noneMatch(command -> command.toUpperCase().startsWith("MAIL FROM"))
                    .noneMatch(command -> command.toUpperCase().startsWith("DATA"));
        }
    }

    @Test
    @DisplayName("F1 — send_serverWithSelfSignedCertificate_failsAndDeliversNothing")
    void send_serverWithSelfSignedCertificate_failsAndDeliversNothing() {
        shippedConfig(GREEN_MAIL_TLS.getSmtps().getPort(),
                "spring.mail.properties.mail.smtp.ssl.enable=true").run(context -> {
            SmtpEmailCodeMailer mailer = new SmtpEmailCodeMailer(
                    new SmtpMailSender(context.getBean(JavaMailSender.class), FROM), SmtpTestSupport.html());

            assertThatThrownBy(() -> mailer.send(MESSAGE))
                    .isInstanceOf(EmailCodeDeliveryException.class)
                    .hasNoCause();
        });

        assertThat(GREEN_MAIL_TLS.getReceivedMessages()).isEmpty();
    }

    /** Control: the same server and settings deliver once the certificate is trusted, so the failure above is the TLS check. */
    @Test
    @DisplayName("F1 — send_serverWithSelfSignedCertificateTrustedExplicitly_delivers")
    void send_serverWithSelfSignedCertificateTrustedExplicitly_delivers() {
        GREEN_MAIL_TLS.setUser("ses-user", "ses-secret");

        shippedConfig(GREEN_MAIL_TLS.getSmtps().getPort(),
                "spring.mail.properties.mail.smtp.ssl.enable=true",
                "spring.mail.properties.mail.smtp.ssl.trust=*",
                "spring.mail.properties.mail.smtp.ssl.checkserveridentity=false").run(context -> {
            new SmtpEmailCodeMailer(new SmtpMailSender(context.getBean(JavaMailSender.class), FROM),
                    SmtpTestSupport.html()).send(MESSAGE);
        });

        assertThat(GREEN_MAIL_TLS.getReceivedMessages()).hasSize(1);
    }

    @Test
    @DisplayName("F4 — start_smtpProviderWithoutSmtpFrom_fails")
    void start_smtpProviderWithoutSmtpFrom_fails() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withUserConfiguration(IncidentMailConfiguration.class, EmailCodeMailConfiguration.class)
                .withBean(MailProperties.class, () -> new MailProperties(MailProperties.Provider.SMTP))
                .withBean(EmailBrandingProperties.class, () -> new EmailBrandingProperties("https://assets.example.org", Map.of()))
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(IllegalStateException.class)
                        .rootCause().hasMessageContaining("SMTP_FROM"));
    }
}
