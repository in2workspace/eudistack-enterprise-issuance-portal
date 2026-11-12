package es.in2.issuanceportal.journey.infrastructure.web;

import es.in2.issuanceportal.journey.application.IssuanceService;
import es.in2.issuanceportal.session.api.SessionId;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.CurrentTenant;
import es.in2.issuanceportal.shared.web.SessionCookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /journey/confirmation} of the contract (annex/openapi.yaml). It takes no data: the body is never read,
 * so neither an email nor any attestation data can come from the interface (FR-05). The email is the one the
 * holder verified with a code.
 */
@RestController
@RequestMapping("/api/v1/journey/confirmation")
class ConfirmationController {

    private final IssuanceService service;

    ConfirmationController(IssuanceService service) {
        this.service = service;
    }

    @PostMapping
    OfferResponse confirm(HttpServletRequest request) {
        return OfferResponse.of(service.confirm(CurrentTenant.of(request), session(request)));
    }

    /** No cookie, or one this service never issued, is a journey that no longer exists. */
    private static SessionId session(HttpServletRequest request) {
        return SessionCookie.read(request).orElseThrow(() -> new ProblemException(ProblemCode.JOURNEY_CLOSED));
    }
}
