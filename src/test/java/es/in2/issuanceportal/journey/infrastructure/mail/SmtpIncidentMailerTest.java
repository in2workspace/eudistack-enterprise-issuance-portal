package es.in2.issuanceportal.journey.infrastructure.mail;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import es.in2.issuanceportal.journey.application.port.IncidentDeliveryException;
import es.in2.issuanceportal.journey.application.port.IncidentMessage;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.IOException;

import static es.in2.issuanceportal.journey.infrastructure.mail.SmtpTestSupport.FROM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SmtpIncidentMailerTest {

    private static final IncidentMessage MESSAGE = new IncidentMessage(
            "soporte@cgcom-identidad.es", "ana@example.org", "[Incidencia] Otro: Duda ñ", "Nombre: Ana Peña\n");

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetup.SMTP.dynamicPort())
            .withPerMethodLifecycle(true);

    @Test
    @DisplayName("AC-04 — send_message_sendsPlainTextUtf8FromPlatformToSupportWithReplyTo")
    void send_message_sendsPlainTextUtf8FromPlatformToSupportWithReplyTo() throws Exception {
        SmtpIncidentMailer mailer = new SmtpIncidentMailer(new SmtpMailSender(
                SmtpTestSupport.senderTo(GREEN_MAIL.getSmtp().getPort()), FROM));

        mailer.send(MESSAGE);

        MimeMessage[] received = GREEN_MAIL.getReceivedMessages();
        assertThat(received).hasSize(1);
        MimeMessage sent = received[0];
        assertThat(sent.getFrom()).extracting(Object::toString).containsExactly(FROM);
        assertThat(sent.getRecipients(Message.RecipientType.TO)).extracting(Object::toString)
                .containsExactly("soporte@cgcom-identidad.es");
        assertThat(sent.getRecipients(Message.RecipientType.CC)).isNull();
        assertThat(sent.getRecipients(Message.RecipientType.BCC)).isNull();
        assertThat(sent.getReplyTo()).extracting(Object::toString).containsExactly("ana@example.org");
        assertThat(sent.getSubject()).isEqualTo("[Incidencia] Otro: Duda ñ");
        assertThat(sent.getContentType()).startsWith("text/plain").containsIgnoringCase("charset=UTF-8");
        // GreenMail stores the body without the line break that ends the SMTP DATA section.
        assertThat(((String) sent.getContent()).stripTrailing()).isEqualTo("Nombre: Ana Peña");
    }

    @Test
    @DisplayName("ES-03 — send_replyToWithHeaderInjection_failsAndSendsNothing")
    void send_replyToWithHeaderInjection_failsAndSendsNothing() {
        SmtpIncidentMailer mailer = new SmtpIncidentMailer(new SmtpMailSender(
                SmtpTestSupport.senderTo(GREEN_MAIL.getSmtp().getPort()), FROM));
        IncidentMessage injected = new IncidentMessage("soporte@cgcom-identidad.es",
                "ana@example.org\r\nBcc: victima@example.org", "[Incidencia] Otro: Duda", "Nombre: Ana\n");

        assertThatThrownBy(() -> mailer.send(injected))
                .isInstanceOf(IncidentDeliveryException.class)
                .hasMessageNotContaining("victima@example.org")
                .hasNoCause();
        assertThat(GREEN_MAIL.getReceivedMessages()).isEmpty();
    }

    @Test
    @DisplayName("ES-03 — send_subjectWithLineBreak_addsNoHeader")
    void send_subjectWithLineBreak_addsNoHeader() throws Exception {
        SmtpIncidentMailer mailer = new SmtpIncidentMailer(new SmtpMailSender(
                SmtpTestSupport.senderTo(GREEN_MAIL.getSmtp().getPort()), FROM));
        IncidentMessage injected = new IncidentMessage("soporte@cgcom-identidad.es", "ana@example.org",
                "Duda\r\nBcc: victima@example.org", "Nombre: Ana\n");

        try {
            mailer.send(injected);
        } catch (IncidentDeliveryException _) {
            // Refusing the message is as good as sending it without the header.
        }

        for (MimeMessage sent : GREEN_MAIL.getReceivedMessages()) {
            assertThat(sent.getHeader("Bcc")).isNull();
            assertThat(sent.getRecipients(Message.RecipientType.BCC)).isNull();
        }
    }

    @Test
    @DisplayName("ES-03 — send_serverRefuses_failsWithTheReasonOnly")
    void send_serverRefuses_failsWithTheReasonOnly() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(SmtpTestSupport.emptyMessage());
        doThrow(new MailSendException("554 Message rejected: soporte@cgcom-identidad.es"))
                .when(sender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> new SmtpIncidentMailer(new SmtpMailSender(sender, FROM)).send(MESSAGE))
                .isInstanceOf(IncidentDeliveryException.class)
                .hasMessageContaining("MailSendException")
                .hasMessageNotContaining("soporte@cgcom-identidad.es")
                .hasNoCause();
    }

    @Test
    @DisplayName("ES-03 — send_serverUnreachable_failsWithoutCause")
    void send_serverUnreachable_failsWithoutCause() throws IOException {
        SmtpIncidentMailer mailer = new SmtpIncidentMailer(new SmtpMailSender(
                SmtpTestSupport.senderTo(SmtpTestSupport.closedPort()), FROM));

        assertThatThrownBy(() -> mailer.send(MESSAGE))
                .isInstanceOf(IncidentDeliveryException.class)
                .hasMessageContaining("MailSendException")
                .hasNoCause();
    }
}
