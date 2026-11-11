package es.in2.issuanceportal.journey.infrastructure.mail;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import es.in2.issuanceportal.journey.application.port.EmailCodeDeliveryException;
import es.in2.issuanceportal.journey.application.port.EmailCodeMessage;
import es.in2.issuanceportal.journey.domain.EmailLanguage;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static es.in2.issuanceportal.journey.infrastructure.mail.SmtpTestSupport.FROM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SmtpEmailCodeMailerTest {

    private static final EmailCodeMessage MESSAGE = EmailCodeMessage.of("ana@example.org", "042042",
            "DoctorID", EmailLanguage.ES, "cgcom");

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetup.SMTP.dynamicPort())
            .withPerMethodLifecycle(true);

    @Test
    @DisplayName("AC-01 — send_message_sendsUtf8HtmlWithTextAlternativeFromThePlatformToTheHolderOnly")
    void send_message_sendsUtf8HtmlWithTextAlternativeFromThePlatformToTheHolderOnly() throws Exception {
        mailer(Map.of()).send(MESSAGE);

        MimeMessage[] received = GREEN_MAIL.getReceivedMessages();
        assertThat(received).hasSize(1);
        MimeMessage sent = received[0];
        assertThat(sent.getFrom()).extracting(Object::toString).containsExactly(FROM);
        assertThat(sent.getRecipients(Message.RecipientType.TO)).extracting(Object::toString)
                .containsExactly("ana@example.org");
        assertThat(sent.getRecipients(Message.RecipientType.CC)).isNull();
        assertThat(sent.getRecipients(Message.RecipientType.BCC)).isNull();
        assertThat(sent.getHeader("Reply-To")).isNull();
        assertThat(sent.getSubject()).isEqualTo("Tu código de verificación — DoctorID");
        assertThat(sent.getContentType()).startsWith("multipart/alternative");

        Multipart alternatives = (Multipart) sent.getContent();
        assertThat(alternatives.getCount()).isEqualTo(2);
        // The plain text comes first and the HTML last, which is the one clients prefer.
        BodyPart text = alternatives.getBodyPart(0);
        assertThat(text.getContentType()).startsWith("text/plain").containsIgnoringCase("charset=UTF-8");
        assertThat((String) text.getContent()).contains("042042", "DoctorID", "Caduca en 10 minutos.")
                .doesNotContain("http");
        BodyPart html = alternatives.getBodyPart(1);
        assertThat(html.getContentType()).startsWith("text/html").containsIgnoringCase("charset=UTF-8");
        assertThat((String) html.getContent()).contains(">042042</span>", "Tu código de verificación",
                "Introdúcelo en el portal de emisión. Caduca en 10 minutos.", "DoctorID",
                "Si no has pedido este código, puedes ignorar este correo.");
    }

    @Test
    @DisplayName("AC-01 — send_english_sendsTheEnglishTextsInBothParts")
    void send_english_sendsTheEnglishTextsInBothParts() throws Exception {
        mailer(Map.of()).send(EmailCodeMessage.of("ana@example.org", "042042", "DoctorID Portal", EmailLanguage.EN,
                "cgcom"));

        MimeMessage sent = GREEN_MAIL.getReceivedMessages()[0];
        assertThat(sent.getSubject()).isEqualTo("Your verification code — DoctorID Portal");
        Multipart alternatives = (Multipart) sent.getContent();
        assertThat((String) alternatives.getBodyPart(0).getContent()).contains("042042", "It expires in 10 minutes.");
        assertThat((String) alternatives.getBodyPart(1).getContent()).contains(">042042</span>", "Hello,",
                "Enter this code in the issuance portal. It expires in 10 minutes.",
                "If you did not request this code, you can safely ignore this email.", "DoctorID Portal");
    }

    @Test
    @DisplayName("AC-01 — send_anyMessage_hasNoLinkInAnyPart")
    void send_anyMessage_hasNoLinkInAnyPart() throws Exception {
        mailer(Map.of("cgcom", new EmailBrandingProperties.TenantEmailBranding(true, true))).send(MESSAGE);

        Multipart alternatives = (Multipart) GREEN_MAIL.getReceivedMessages()[0].getContent();
        String text = (String) alternatives.getBodyPart(0).getContent();
        String html = (String) alternatives.getBodyPart(1).getContent();
        assertThat(text).doesNotContain("http", "://", "www.");
        assertThat(html).doesNotContainPattern("<a[\\s>]").doesNotContain("mailto:", "href=", "<link",
                "fonts.googleapis.com");
        // The only external addresses are the tenant's images, from the assets base URL.
        Matcher urls = Pattern.compile("https?://[^\\s\"')<>]+").matcher(html);
        while (urls.find()) {
            assertThat(urls.group()).startsWith(SmtpTestSupport.ASSETS + "/cgcom/email/");
        }
    }

    @Test
    @DisplayName("AC-01 — send_tenantWithBranding_showsLogoAndHeaderBackgroundFromTheAssets")
    void send_tenantWithBranding_showsLogoAndHeaderBackgroundFromTheAssets() throws Exception {
        mailer(Map.of("cgcom", new EmailBrandingProperties.TenantEmailBranding(true, true))).send(MESSAGE);

        String html = htmlOfTheOnlyMessage();
        assertThat(html).contains("src=\"" + SmtpTestSupport.ASSETS + "/cgcom/email/logo.png\"")
                .contains("alt=\"DoctorID\"")
                .contains("background-image: url(" + SmtpTestSupport.ASSETS + "/cgcom/email/header-bg.png)");
    }

    @Test
    @DisplayName("AC-01 — send_tenantWithOnlyTheLogo_showsNoHeaderBackground")
    void send_tenantWithOnlyTheLogo_showsNoHeaderBackground() throws Exception {
        mailer(Map.of("cgcom", new EmailBrandingProperties.TenantEmailBranding(true, false))).send(MESSAGE);

        assertThat(htmlOfTheOnlyMessage()).contains("/cgcom/email/logo.png").doesNotContain("header-bg")
                .doesNotContain("background-image");
    }

    @Test
    @DisplayName("AC-01 — send_tenantWithoutBranding_showsNoImage")
    void send_tenantWithoutBranding_showsNoImage() throws Exception {
        mailer(Map.of("kpmg", new EmailBrandingProperties.TenantEmailBranding(true, true))).send(MESSAGE);

        assertThat(htmlOfTheOnlyMessage()).doesNotContain("<img").doesNotContain("background-image")
                .doesNotContain("/kpmg/");
    }

    @Test
    @DisplayName("AC-06 — send_serverRefuses_failsWithTheReasonOnly")
    void send_serverRefuses_failsWithTheReasonOnly() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(SmtpTestSupport.emptyMessage());
        doThrow(new MailSendException("550 5.1.1 Address rejected: ana@example.org 042042"))
                .when(sender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> new SmtpEmailCodeMailer(new SmtpMailSender(sender, FROM), SmtpTestSupport.html())
                .send(MESSAGE))
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

        assertThatThrownBy(() -> new SmtpEmailCodeMailer(new SmtpMailSender(sender, FROM), SmtpTestSupport.html())
                .send(MESSAGE))
                .isInstanceOf(EmailCodeDeliveryException.class)
                .hasMessageContaining("MailAuthenticationException")
                .hasNoCause();
    }

    @Test
    @DisplayName("AC-06 — send_serverUnreachable_failsWithoutCause")
    void send_serverUnreachable_failsWithoutCause() throws IOException {
        SmtpEmailCodeMailer mailer = new SmtpEmailCodeMailer(new SmtpMailSender(
                SmtpTestSupport.senderTo(SmtpTestSupport.closedPort()), FROM), SmtpTestSupport.html());

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
                                new SmtpMailSender(context.getBean(JavaMailSender.class), FROM),
                                SmtpTestSupport.html());
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

    private SmtpEmailCodeMailer mailer(Map<String, EmailBrandingProperties.TenantEmailBranding> tenants) {
        return new SmtpEmailCodeMailer(new SmtpMailSender(
                SmtpTestSupport.senderTo(GREEN_MAIL.getSmtp().getPort()), FROM), SmtpTestSupport.html(tenants));
    }

    private static String htmlOfTheOnlyMessage() throws Exception {
        MimeMessage[] received = GREEN_MAIL.getReceivedMessages();
        assertThat(received).hasSize(1);
        return (String) ((Multipart) received[0].getContent()).getBodyPart(1).getContent();
    }
}
