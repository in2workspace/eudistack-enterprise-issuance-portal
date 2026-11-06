package es.in2.issuanceportal.journey.application.port;

/**
 * There is no system token for the tenant. It carries only the reason, never the cause: the messages of the
 * secret store or the Verifier may quote the credential, the assertion or the token.
 */
public class SystemTokenUnavailableException extends RuntimeException {

    public enum Reason {
        /** The tenant has no machine credential in the secret store, or it cannot be used. */
        NO_MACHINE_CREDENTIAL,
        /** The secret store did not answer. */
        SECRET_STORE_UNAVAILABLE,
        /** The Verifier answered but did not grant a token. */
        VERIFIER_REJECTED,
        /** The Verifier did not answer in time, or failed. */
        VERIFIER_UNREACHABLE
    }

    private final transient Reason reason;

    public SystemTokenUnavailableException(Reason reason) {
        super("System token unavailable: " + reason, null, false, false);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
