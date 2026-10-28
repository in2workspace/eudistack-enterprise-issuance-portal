package es.in2.issuanceportal.authenticsource.infrastructure.web;

import es.in2.issuanceportal.authenticsource.application.EntitlementService;
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
 * {@code /entitlement} of the contract (annex/openapi.yaml). It takes no body: whatever the interface
 * sends is never read, so no holder data can come from it (FR-02).
 */
@RestController
@RequestMapping("/api/v1/entitlement")
class EntitlementController {

    private final EntitlementService service;

    EntitlementController(EntitlementService service) {
        this.service = service;
    }

    @PostMapping
    EntitlementResponse verify(HttpServletRequest request) {
        return EntitlementResponse.of(service.check(CurrentTenant.of(request), session(request)));
    }

    /**
     * A holder without a journey in progress asks out of order (ac.md decision 4). A session that no
     * longer leads to one is a closed journey, answered by the journey's own check.
     */
    private static SessionId session(HttpServletRequest request) {
        return SessionCookie.read(request).orElseThrow(() -> new ProblemException(ProblemCode.STEP_NOT_ALLOWED));
    }
}
