package es.in2.issuanceportal.journey.infrastructure.mail;

import es.in2.issuanceportal.journey.application.port.IncidentDeliveryException;
import es.in2.issuanceportal.journey.application.port.IncidentMailer;
import es.in2.issuanceportal.journey.application.port.IncidentMessage;

/** Sends the incident by SMTP, from the platform's verified sending identity (design.md AD-8). */
class SmtpIncidentMailer implements IncidentMailer {

    private final SmtpMailSender sender;

    SmtpIncidentMailer(SmtpMailSender sender) {
        this.sender = sender;
    }

    @Override
    public void send(IncidentMessage message) {
        try {
            sender.send(message.to(), message.replyTo(), message.subject(), message.body());
        } catch (SmtpMailSender.SmtpMailFailure e) {
            throw new IncidentDeliveryException(e.getMessage());
        }
    }
}
