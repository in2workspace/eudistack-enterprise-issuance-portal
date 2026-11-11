package es.in2.issuanceportal.journey.infrastructure.mail;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import es.in2.issuanceportal.journey.application.port.EmailCodeDeliveryException;
import es.in2.issuanceportal.journey.application.port.EmailCodeMessage;
import es.in2.issuanceportal.journey.domain.EmailLanguage;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.IOException;
import java.time.Duration;

import static es.in2.issuanceportal.journey.infrastructure.mail.SmtpTestSupport.FROM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SmtpEmailCodeMailerTest {

    private static final EmailCodeMessage MESSAGE = EmailCodeMessage.of("ana@example.org", "042042",
            "DoctorID", EmailLanguage.ES);

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetup.SMTP.dynamicPort());

    @Test
    @DisplayName("AC-01 — send_message_sendsPlainTextUtf8FromThePlatformToTheHolderOnly")
    void send_message_sendsPlainTextUtf8FromThePlatformToTheHolderOnly() throws Exception {
        SmtpEmailCodeMailer mailer = new SmtpEmailCodeMailer(new SmtpMailSender(
                SmtpTestSupport.senderTo(GREEN_MAIL.getSmtp().getPort()), FROM));

        mailer.send(MESSAGE);

        MimeMessage[] received = GREEN_MAIL.getReceivedMessages();
        assertThat(received).hasSize(1);
        MimeMessage sent = received[0];
        assertThat(sent.getFrom()).extracting(Object::toString).containsExactly(FROM);
        assertThat(sent.getRecipients(Message.RecipientType.TO)).extracting(Object::toString)
                .containsExactly("ana@example.org");
        assertThat(sent.getRecipients(Message.RecipientType.CC)).isNull();
        assertThat(sent.getRecipients(Message.RecipientType.BCC)).isNull();
        assertThat(sent.getHeader("Reply-To")).isNull();
        assertThat(sent.getSubject()).isEqualTo("Tu código de verificación");
        assertThat(sent.getContentType()).startsWith("text/plain").containsIgnoringCase("charset=UTF-8");
        String body = (String) sent.getContent();
        assertThat(body).contains("042042", "DoctorID", "Caduca en 10 minutos.");
        assertThat(body).doesNotContain("http");
    }

    @Test
    @DisplayName("AC-06 — send_serverRefuses_failsWithTheReasonOnly")
    void send_serverRefuses_failsWithTheReasonOnly() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(SmtpTestSupport.emptyMessage());
        doThrow(new MailSendException("550 5.1.1 Address rejected: ana@example.org 042042"))
                .when(sender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> new SmtpEmailCodeMailer(new SmtpMailSender(sender, FROM)).send(MESSAGE))
                .isInstanceOf(EmailCodeDeliveryException.class)
                .hasMessageContaining("MailSendException")
                .hasMessageNotContaining("ana@example.org")
                .hasMessageNotContaining("042042")
                .hasNoCause();
    }

    @Test
    @DisplayName("AC-06 — send_authenticationFails_failsWithTheReasonOnly")
    void send_authenticationFails_failsWithTheReasonOnly() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(SmtpTestSupport.emptyMessage());
        doThrow(new MailAuthenticationException("535 Authentication Credentials Invalid"))
                .when(sender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> new SmtpEmailCodeMailer(new SmtpMailSender(sender, FROM)).send(MESSAGE))
                .isInstanceOf(EmailCodeDeliveryException.class)
                .hasMessageContaining("MailAuthenticationException")
                .hasNoCause();
    }

    @Test
    @DisplayName("AC-06 — send_serverUnreachable_failsWithoutCause")
    void send_serverUnreachable_failsWithoutCause() throws IOException {
        SmtpEmailCodeMailer mailer = new SmtpEmailCodeMailer(new SmtpMailSender(
                SmtpTestSupport.senderTo(SmtpTestSupport.closedPort()), FROM));

        assertThatThrownBy(() -> mailer.send(MESSAGE))
                .isInstanceOf(EmailCodeDeliveryException.class)
                .hasMessageContaining("MailSendException")
                .hasMessageNotContaining("ana@example.org")
                .hasNoCause();
    }

    /**
     * NFR-1: an SMTP server that accepts the connection and never answers is a failed send after the timeouts
     * the service configures by default (read from its own application.yaml), not a request that waits.
     */
    @Test
    @DisplayName("NFR-1 — send_serverThatNeverAnswers_failsAtTheFiveSecondLimit")
    void send_serverThatNeverAnswers_failsAtTheFiveSecondLimit() throws IOException {
        try (SmtpTestSupport.SilentServer silent = new SmtpTestSupport.SilentServer()) {
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                    .withPropertyValues("spring.mail.host=127.0.0.1", "spring.mail.port=" + silent.port(),
                            "spring.mail.properties.mail.smtp.auth=false",
                            "spring.mail.properties.mail.smtp.starttls.enable=false")
                    .run(context -> {
                        SmtpEmailCodeMailer silentMailer = new SmtpEmailCodeMailer(
                                new SmtpMailSender(context.getBean(JavaMailSender.class), FROM));
                        long started = System.nanoTime();

                        assertThatThrownBy(() -> silentMailer.send(MESSAGE))
                                .isInstanceOf(EmailCodeDeliveryException.class)
                                .hasMessageContaining("MailSendException")
                                .hasNoCause();

                        Duration waited = Duration.ofNanos(System.nanoTime() - started);
                        assertThat(waited).isBetween(Duration.ofMillis(4_500), Duration.ofSeconds(8));
                    });
        }
    }
}
