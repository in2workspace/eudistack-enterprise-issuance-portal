package es.in2.issuanceportal.journey.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import es.in2.issuanceportal.journey.domain.EmailVerification;

import java.time.Instant;

/**
 * {@code EmailVerification} of the contract (annex/openapi.yaml): the email of the pending or completed
 * verification. The expiry is only there while a code is pending. Personal data: never in the text form.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record EmailVerificationResponse(
        String email,
        boolean verified,
        @JsonProperty("code_expires_at") Instant codeExpiresAt,
        @JsonProperty("resend_available_at") Instant resendAvailableAt,
        @JsonProperty("sends_left") int sendsLeft) {

    static EmailVerificationResponse of(EmailVerification verification) {
        return new EmailVerificationResponse(verification.pendingEmail(), verification.isVerified(),
                verification.isPending() ? verification.codeExpiresAt() : null,
                verification.resendAvailableAt(), verification.sendsLeft());
    }

    @Override
    public String toString() {
        return "EmailVerificationResponse[email redacted, verified=" + verified + ", sendsLeft=" + sendsLeft + "]";
    }
}
