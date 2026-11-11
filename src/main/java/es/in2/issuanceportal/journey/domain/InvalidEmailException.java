package es.in2.issuanceportal.journey.domain;

import java.util.Locale;

/** The email of the confirmation is missing or is not an email address. It never carries the value. */
public class InvalidEmailException extends RuntimeException {

    public enum Reason { REQUIRED, INVALID }

    private final transient Reason reason;

    public InvalidEmailException(Reason reason) {
        super("email_" + reason.name().toLowerCase(Locale.ROOT));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
