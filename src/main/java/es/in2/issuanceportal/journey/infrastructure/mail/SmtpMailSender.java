package es.in2.issuanceportal.journey.infrastructure.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.nio.charset.StandardCharsets;

/**
 * Sends a plain-text UTF-8 message through the SMTP server of {@code spring.mail} (the SES SMTP interface). The
 * connection, read and write timeouts of the sender bound the call. A failure keeps only the class of the
 * error: the messages of a mail library may quote the addresses involved.
 */
class SmtpMailSender {

    private final JavaMailSender mailSender;
    private final String from;

    SmtpMailSender(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    /**
     * @param replyTo {@code null} when the message has none
     * @throws SmtpMailFailure when the message could not be built or the server did not accept it
     */
    void send(String to, String replyTo, String subject, String body) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(to);
            if (replyTo != null) {
                helper.setReplyTo(replyTo);
            }
            helper.setSubject(subject);
            helper.setText(body, false);
            mailSender.send(message);
        } catch (MessagingException e) {
            throw new SmtpMailFailure("SMTP message invalid (" + e.getClass().getSimpleName() + ")");
        } catch (MailException e) {
            throw new SmtpMailFailure("SMTP " + e.getClass().getSimpleName());
        }
    }

    /** Carries only a reason code: no cause, no stack trace. */
    static final class SmtpMailFailure extends RuntimeException {

        SmtpMailFailure(String reason) {
            super(reason, null, false, false);
        }
    }
}
