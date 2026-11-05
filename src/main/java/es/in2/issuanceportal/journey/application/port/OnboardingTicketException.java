package es.in2.issuanceportal.journey.application.port;

/**
 * The onboarding ticket was not created. It carries only the reason, never the cause, the email or the offer.
 */
public class OnboardingTicketException extends RuntimeException {

    public enum Reason {
        /** The tenant has no system token, so the Wallet Unit Service was not asked. */
        SYSTEM_TOKEN_UNAVAILABLE,
        /** The Wallet Unit Service answered 401: the cached system token was invalidated. */
        NOT_AUTHORIZED,
        /** The Wallet Unit Service refused the request (another 4xx). */
        REJECTED,
        /** No answer in time, a connection error or a 5xx. */
        UNAVAILABLE,
        /** A success without the address or the lifetime of the ticket, or one that cannot be read. */
        UNEXPECTED_RESPONSE
    }

    private final transient Reason reason;

    public OnboardingTicketException(Reason reason) {
        super("Onboarding ticket not created: " + reason, null, false, false);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
