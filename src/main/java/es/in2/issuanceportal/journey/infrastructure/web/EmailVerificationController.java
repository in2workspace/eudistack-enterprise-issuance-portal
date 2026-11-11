package es.in2.issuanceportal.journey.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import es.in2.issuanceportal.journey.application.EmailVerificationService;
import es.in2.issuanceportal.journey.application.JourneyViewService;
import es.in2.issuanceportal.journey.domain.EmailLanguage;
import es.in2.issuanceportal.journey.domain.EmailVerification;
import es.in2.issuanceportal.session.api.SessionId;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.CurrentTenant;
import es.in2.issuanceportal.shared.tenant.TenantId;
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

/**
 * {@code POST /journey/email-verification} and {@code /code} of the contract (annex/openapi.yaml): sends the
 * holder's email a code, and checks the code the holder typed. The address of the limit per client is the one the platform's proxies saw, never one the client wrote
 * ({@code ClientAddressFilter}).
 */
@RestController
@RequestMapping("/api/v1/journey/email-verification")
class EmailVerificationController {

    private final EmailVerificationService service;
    private final JourneyViewService views;

    EmailVerificationController(EmailVerificationService service, JourneyViewService views) {
        this.service = service;
        this.views = views;
    }

    /** The code as sent: only text can be one (the contract says a string of 6 digits), a number is not. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CheckRequest(JsonNode code) {

        String codeText() {
            return code != null && code.isTextual() ? code.asText() : null;
        }
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

    /** The journey as {@code GET /journey} gives it, so the interface sees at once that the email is verified. */
    @PostMapping("/code")
    JourneyResponse check(HttpServletRequest request, @RequestBody(required = false) CheckRequest body) {
        TenantId tenant = CurrentTenant.of(request);
        SessionId session = session(request);
        service.check(tenant, session, body == null ? null : body.codeText());
        return JourneyResponse.of(views.current(tenant, session));
    }

    /** No cookie, or one this service never issued, is a journey that no longer exists. */
    private static SessionId session(HttpServletRequest request) {
        return SessionCookie.read(request).orElseThrow(() -> new ProblemException(ProblemCode.JOURNEY_CLOSED));
    }
}
