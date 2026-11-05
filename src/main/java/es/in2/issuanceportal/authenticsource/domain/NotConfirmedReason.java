package es.in2.issuanceportal.authenticsource.domain;

/** Why the source did not confirm the entitlement (openapi {@code EntitlementResult.reason}). */
public enum NotConfirmedReason {

    /** The holder is not in the source. */
    NOT_FOUND("not_found"),
    /** The holder is in the source without entitlement, for example deregistered. */
    NOT_ENTITLED("not_entitled"),
    /** The source returned a person whose identifier of the searched type is not the one searched. */
    IDENTIFIER_MISMATCH("identifier_mismatch");

    private final String code;

    NotConfirmedReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
