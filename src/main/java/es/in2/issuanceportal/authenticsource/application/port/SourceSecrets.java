package es.in2.issuanceportal.authenticsource.application.port;

/** The credentials of the tenants' sources, kept outside the configuration (design.md AD-4). */
public interface SourceSecrets {

    /**
     * The credential kept under {@code reference}, read at the time of use.
     *
     * @throws SourceSecretUnavailableException when it cannot be read; never with the secret
     */
    String secret(String reference);

    /** The credential cannot be read. It carries nothing: the store's messages may quote the reference or the secret. */
    class SourceSecretUnavailableException extends RuntimeException {

        public SourceSecretUnavailableException() {
            super("Source secret unavailable", null, false, false);
        }
    }
}
