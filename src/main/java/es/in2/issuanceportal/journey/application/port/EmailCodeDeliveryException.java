package es.in2.issuanceportal.journey.application.port;

/**
 * The code email could not be delivered. It keeps only an error code, never the cause: the messages of a
 * mail provider may quote the address or the content.
 */
public class EmailCodeDeliveryException extends RuntimeException {

    public EmailCodeDeliveryException(String reason) {
        super("Code email not delivered: " + reason, null, false, false);
    }
}
