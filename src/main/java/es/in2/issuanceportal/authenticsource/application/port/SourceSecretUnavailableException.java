package es.in2.issuanceportal.authenticsource.application.port;

/** The credential cannot be read. It carries nothing: the store's messages may quote the reference or the secret. */
public class SourceSecretUnavailableException extends RuntimeException {

    public SourceSecretUnavailableException() {
        super("Source secret unavailable", null, false, false);
    }
}
