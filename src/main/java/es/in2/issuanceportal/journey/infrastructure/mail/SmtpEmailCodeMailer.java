package es.in2.issuanceportal.journey.infrastructure.mail;

import es.in2.issuanceportal.journey.application.port.EmailCodeDeliveryException;
import es.in2.issuanceportal.journey.application.port.EmailCodeMailer;
import es.in2.issuanceportal.journey.application.port.EmailCodeMessage;
import org.thymeleaf.exceptions.TemplateEngineException;

/**
 * Sends the verification code by SMTP, from the platform's verified sending identity, the one the incidents use
 * (design.md §3.6): the wallet's OTP email as HTML, with the plain text as the alternative for text-only
 * clients. The timeouts of the sender make a server that does not answer a failed send.
 */
class SmtpEmailCodeMailer implements EmailCodeMailer {

    private final SmtpMailSender sender;
    private final EmailCodeHtml html;

    SmtpEmailCodeMailer(SmtpMailSender sender, EmailCodeHtml html) {
        this.sender = sender;
        this.html = html;
    }

    @Override
    public void send(EmailCodeMessage message) {
        String rendered;
        try {
            rendered = html.render(message);
        } catch (TemplateEngineException e) {
            // The cause is dropped: the template context holds the code.
            throw new EmailCodeDeliveryException("template " + e.getClass().getSimpleName());
        }
        try {
            sender.sendAlternative(message.to(), message.subject(), message.body(), rendered);
        } catch (SmtpMailSender.SmtpMailFailure e) {
            throw new EmailCodeDeliveryException(e.getMessage());
        }
    }
}
