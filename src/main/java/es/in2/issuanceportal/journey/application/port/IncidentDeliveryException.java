package es.in2.issuanceportal.journey.application.port;

/**
 * The incident could not be delivered. It keeps only an error code, never the cause: the messages of a
 * mail provider may quote the addresses involved.
 */
public class IncidentDeliveryException extends RuntimeException {

    public IncidentDeliveryException(String reason) {
        super("Incident not delivered: " + reason, null, false, false);
    }
}
