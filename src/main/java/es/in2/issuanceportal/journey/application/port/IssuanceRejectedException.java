package es.in2.issuanceportal.journey.application.port;

/**
 * The Issuer did not issue: it refused the request, or answered without what the portal needs. Retrying the
 * same request does not help. It carries only the reason, never the cause or the response, which may quote
 * personal data or the offer.
 */
public class IssuanceRejectedException extends RuntimeException {

    public enum Reason {
        /** The Issuer answered 4xx. */
        REQUEST_REJECTED,
        /** The Issuer did not accept the system token (401). */
        NOT_AUTHORIZED,
        /** The answer is not a success, or lacks a valid {@code Location} or the offer. */
        UNEXPECTED_RESPONSE
    }

    private final transient Reason reason;

    public IssuanceRejectedException(Reason reason) {
        super("Issuance rejected: " + reason, null, false, false);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
