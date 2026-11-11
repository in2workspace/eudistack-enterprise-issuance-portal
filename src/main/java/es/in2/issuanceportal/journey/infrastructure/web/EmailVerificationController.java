package es.in2.issuanceportal.journey.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import es.in2.issuanceportal.journey.application.EmailVerificationService;
import es.in2.issuanceportal.journey.domain.EmailLanguage;
import es.in2.issuanceportal.journey.domain.EmailVerification;
import es.in2.issuanceportal.session.api.SessionId;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.CurrentTenant;
import es.in2.issuanceportal.shared.web.SessionCookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * {@code POST /journey/email-verification} of the contract (annex/openapi.yaml): sends the holder's email a
 * code. The address of the limit per client is the one the platform's proxies saw, never one the client wrote
 * ({@code ClientAddressFilter}).
 */
@RestController
@RequestMapping("/api/v1/journey/email-verification")
class EmailVerificationController {

    private final EmailVerificationService service;

    EmailVerificationController(EmailVerificationService service) {
        this.service = service;
    }

    /** The email as sent: a value that is not text is passed on as its JSON text, which is never an email. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SendRequest(JsonNode email) {

        String emailText() {
            if (email == null || email.isNull()) {
                return null;
            }
            return email.isTextual() ? email.asText() : email.toString();
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    EmailVerificationResponse send(HttpServletRequest request,
                                   @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
                                   @RequestBody(required = false) SendRequest body) {
        EmailVerification sent = service.send(CurrentTenant.of(request), session(request),
                body == null ? null : body.emailText(), EmailLanguage.fromAcceptLanguage(acceptLanguage),
                request.getRemoteAddr());
        return EmailVerificationResponse.of(sent);
    }

    /** No cookie, or one this service never issued, is a journey that no longer exists. */
    private static SessionId session(HttpServletRequest request) {
        return SessionCookie.read(request).orElseThrow(() -> new ProblemException(ProblemCode.JOURNEY_CLOSED));
    }

    /** {@code EmailVerification} of the contract, for a code that was just sent: still to be verified. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record EmailVerificationResponse(
            String email,
            boolean verified,
            @JsonProperty("code_expires_at") Instant codeExpiresAt,
            @JsonProperty("resend_available_at") Instant resendAvailableAt,
            @JsonProperty("sends_left") int sendsLeft) {

        static EmailVerificationResponse of(EmailVerification sent) {
            return new EmailVerificationResponse(sent.pendingEmail(), false, sent.codeExpiresAt(),
                    sent.resendAvailableAt(), sent.sendsLeft());
        }

        /** Personal data: never in the text form. */
        @Override
        public String toString() {
            return "EmailVerificationResponse[email redacted, verified=" + verified + ", sendsLeft=" + sendsLeft + "]";
        }
    }
}
