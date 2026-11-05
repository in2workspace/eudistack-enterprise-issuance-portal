package es.in2.issuanceportal.session.api;

/**
 * A section of the journey session whose content does not have the shape of its contract. The message
 * is the path of the field that fails, never its value.
 */
public class InvalidSectionException extends RuntimeException {

    private final String field;

    public InvalidSectionException(String field) {
        super(field);
        this.field = field;
    }

    public InvalidSectionException(String field, Throwable cause) {
        super(field, cause);
        this.field = field;
    }

    /** The path of the field that fails, e.g. {@code personal_identifier.type}. */
    public String field() {
        return field;
    }
}
