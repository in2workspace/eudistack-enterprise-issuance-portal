package es.in2.issuanceportal.authenticsource.domain;

/**
 * Why no request was checked (openapi {@code EntitlementResult.reason}). A holder with requests none of which
 * is available has no reason: each request says why.
 */
public enum NotConfirmedReason {

    /** The holder is not in the source. */
    NOT_FOUND("not_found"),
    /** The holder is in the source without any request. */
    NO_REQUESTS("no_requests");

    private final String code;

    NotConfirmedReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
