package es.in2.issuanceportal.journey.application.port;

/** Delivers the verification code to the holder's email. */
public interface EmailCodeMailer {

    /** @throws EmailCodeDeliveryException if the message could not be handed over for delivery */
    void send(EmailCodeMessage message);
}
