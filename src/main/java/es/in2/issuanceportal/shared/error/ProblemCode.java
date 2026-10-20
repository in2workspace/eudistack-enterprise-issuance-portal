package es.in2.issuanceportal.shared.error;

/**
 * The error codes the API answers with, each with its HTTP status. The code travels as the
 * {@code type} of an RFC 9457 Problem, as the contract defines.
 */
public enum ProblemCode {

    REQUEST_NOT_ALLOWED("request_not_allowed", "Request not allowed", 403),
    TENANT_NOT_FOUND("tenant_not_found", "Tenant not found", 404),
    STEP_NOT_ALLOWED("step_not_allowed", "Step not allowed", 409),
    JOURNEY_CLOSED("journey_closed", "Journey closed", 410),
    SERVICE_UNAVAILABLE("service_unavailable", "Service unavailable", 503);

    private final String code;
    private final String title;
    private final int status;

    ProblemCode(String code, String title, int status) {
        this.code = code;
        this.title = title;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public int status() {
        return status;
    }
}
