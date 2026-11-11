package es.in2.issuanceportal.journey.infrastructure.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.nio.charset.StandardCharsets;

/**
 * Sends a UTF-8 message through the SMTP server of {@code spring.mail} (the SES SMTP interface): plain text, or
 * plain text and HTML as alternatives. The connection, read and write timeouts of the sender bound the call. A
 * failure keeps only the class of the error: the messages of a mail library may quote the addresses involved.
 */
class SmtpMailSender {

    private static final String UTF_8 = StandardCharsets.UTF_8.name();

    private final JavaMailSender mailSender;
    private final String from;

    SmtpMailSender(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    /**
     * Sends a plain-text message.
     *
     * @param replyTo {@code null} when the message has none
     * @throws SmtpMailFailure when the message could not be built or the server did not accept it
     */
    void send(String to, String replyTo, String subject, String body) {
        deliver(to, replyTo, subject, (message, helper) -> helper.setText(body, false));
    }

    /**
     * Sends a multipart/alternative message: the plain text first and the HTML, which clients prefer, last.
     *
     * @throws SmtpMailFailure when the message could not be built or the server did not accept it
     */
    void sendAlternative(String to, String subject, String text, String html) {
        deliver(to, null, subject, (message, helper) -> {
            MimeBodyPart plain = new MimeBodyPart();
            plain.setText(text, UTF_8);
            MimeBodyPart rich = new MimeBodyPart();
            rich.setText(html, UTF_8, "html");
            MimeMultipart alternatives = new MimeMultipart("alternative");
            alternatives.addBodyPart(plain);
            alternatives.addBodyPart(rich);
            message.setContent(alternatives);
        });
    }

    private void deliver(String to, String replyTo, String subject, Body body) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, UTF_8);
            helper.setFrom(from);
            helper.setTo(to);
            if (replyTo != null) {
                helper.setReplyTo(replyTo);
            }
            helper.setSubject(subject);
            body.set(message, helper);
            mailSender.send(message);
        } catch (MessagingException e) {
            throw new SmtpMailFailure("SMTP message invalid (" + e.getClass().getSimpleName() + ")");
        } catch (MailException e) {
            throw new SmtpMailFailure("SMTP " + e.getClass().getSimpleName());
        }
    }

    @FunctionalInterface
    private interface Body {

        void set(MimeMessage message, MimeMessageHelper helper) throws MessagingException;
    }

    /** Carries only a reason code: no cause, no stack trace. */
    static final class SmtpMailFailure extends RuntimeException {

        SmtpMailFailure(String reason) {
            super(reason, null, false, false);
        }
    }
}
