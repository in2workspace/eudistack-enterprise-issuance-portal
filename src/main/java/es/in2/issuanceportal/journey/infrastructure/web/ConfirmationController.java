package es.in2.issuanceportal.journey.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import es.in2.issuanceportal.journey.application.IssuanceService;
import es.in2.issuanceportal.session.api.SessionId;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.CurrentTenant;
import es.in2.issuanceportal.shared.web.SessionCookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /journey/confirmation} of the contract (annex/openapi.yaml). Only {@code email} is read from the
 * body: any other field is ignored, so no attestation data can come from the interface (FR-05).
 */
@RestController
@RequestMapping("/api/v1/journey/confirmation")
class ConfirmationController {

    private final IssuanceService service;

    ConfirmationController(IssuanceService service) {
        this.service = service;
    }

    /** The email as sent: a value that is not text is passed on as its JSON text, which is never an email. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ConfirmationRequest(JsonNode email) {

        String emailText() {
            if (email == null || email.isNull()) {
                return null;
            }
            return email.isTextual() ? email.asText() : email.toString();
        }
    }

    @PostMapping
    OfferResponse confirm(HttpServletRequest request, @RequestBody(required = false) ConfirmationRequest body) {
        String email = body == null ? null : body.emailText();
        return OfferResponse.of(service.confirm(CurrentTenant.of(request), session(request), email));
    }

    /** No cookie, or one this service never issued, is a journey that no longer exists. */
    private static SessionId session(HttpServletRequest request) {
        return SessionCookie.read(request).orElseThrow(() -> new ProblemException(ProblemCode.JOURNEY_CLOSED));
    }
}
