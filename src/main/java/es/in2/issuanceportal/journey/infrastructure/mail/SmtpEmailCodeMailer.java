package es.in2.issuanceportal.journey.infrastructure.mail;

import es.in2.issuanceportal.journey.application.port.EmailCodeDeliveryException;
import es.in2.issuanceportal.journey.application.port.EmailCodeMailer;
import es.in2.issuanceportal.journey.application.port.EmailCodeMessage;

/**
 * Sends the verification code by SMTP, from the platform's verified sending identity, the one the incidents use
 * (design.md §3.6). The timeouts of the sender make a server that does not answer a failed send.
 */
class SmtpEmailCodeMailer implements EmailCodeMailer {

    private final SmtpMailSender sender;

    SmtpEmailCodeMailer(SmtpMailSender sender) {
        this.sender = sender;
    }

    @Override
    public void send(EmailCodeMessage message) {
        try {
            sender.send(message.to(), null, message.subject(), message.body());
        } catch (SmtpMailSender.SmtpMailFailure e) {
            throw new EmailCodeDeliveryException(e.getMessage());
        }
    }
}
