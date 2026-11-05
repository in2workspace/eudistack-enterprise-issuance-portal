package es.in2.issuanceportal.journey.application.port;

/**
 * The Issuer did not answer, also after the retries. The same idempotency key can be used again later. It
 * carries only the reason, never the cause.
 */
public class IssuanceUnavailableException extends RuntimeException {

    public enum Reason {
        /** No answer: connection error or timeout, on every attempt. */
        NETWORK,
        /** The Issuer answered 5xx, on every attempt or on one that is not retried. */
        SERVER_ERROR
    }

    private final transient Reason reason;

    public IssuanceUnavailableException(Reason reason) {
        super("Issuance unavailable: " + reason, null, false, false);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
