package es.in2.issuanceportal.shared.error;

/**
 * The error codes the API answers with, each with its HTTP status. The code travels as the
 * {@code type} of an RFC 9457 Problem, as the contract defines.
 */
public enum ProblemCode {

    BAD_REQUEST("bad_request", "Bad request", 400),
    EMAIL_REQUIRED("email_required", "Email required", 400),
    EMAIL_INVALID("email_invalid", "Email invalid", 400),
    INVALID_CODE("invalid_code", "Invalid code", 400),
    EXPIRED_CODE("expired_code", "Expired code", 400),
    TOO_MANY_ATTEMPTS("too_many_attempts", "Too many attempts", 400),
    REQUEST_NOT_ALLOWED("request_not_allowed", "Request not allowed", 403),
    TENANT_NOT_FOUND("tenant_not_found", "Tenant not found", 404),
    STEP_NOT_ALLOWED("step_not_allowed", "Step not allowed", 409),
    JOURNEY_CLOSED("journey_closed", "Journey closed", 410),
    RESEND_TOO_SOON("resend_too_soon", "Resend too soon", 429),
    TOO_MANY_CODES("too_many_codes", "Too many codes", 429),
    TOO_MANY_REQUESTS("too_many_requests", "Too many requests", 429),
    ISSUANCE_REJECTED("issuance_rejected", "Issuance rejected", 502),
    SERVICE_UNAVAILABLE("service_unavailable", "Service unavailable", 503),
    CONFIG_UNAVAILABLE("config_unavailable", "Portal configuration unavailable", 503),
    AUTHENTIC_SOURCE_UNAVAILABLE("authentic_source_unavailable", "Authentic source unavailable", 503),
    ISSUANCE_UNAVAILABLE("issuance_unavailable", "Issuance unavailable", 503),
    ONBOARDING_UNAVAILABLE("onboarding_unavailable", "Onboarding unavailable", 503),
    EMAIL_UNAVAILABLE("email_unavailable", "Email unavailable", 503);

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
