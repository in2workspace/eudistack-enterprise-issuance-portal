package es.in2.issuanceportal.journey.application.port;

/** Delivers an incident to the tenant's support. */
public interface IncidentMailer {

    /** @throws IncidentDeliveryException if the message could not be handed over for delivery */
    void send(IncidentMessage message);
}
