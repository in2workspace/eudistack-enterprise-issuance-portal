package es.in2.issuanceportal.authenticsource.infrastructure.web;

import es.in2.issuanceportal.authenticsource.application.EntitlementService;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.security.HolderPrincipal;
import es.in2.issuanceportal.shared.security.HolderRefs;
import es.in2.issuanceportal.shared.tenant.CurrentTenant;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /entitlement} of the contract (annex/openapi.yaml). It takes no body: whatever the interface
 * sends is never read, so no holder data can come from it (FR-02).
 */
@RestController
@RequestMapping("/api/v1/entitlement")
class EntitlementController {

    /** Longest {@code id} of a request the contract admits. */
    private static final int MAX_REQUEST_ID_LENGTH = 64;

    private final EntitlementService service;
    private final HolderRefs holders;

    EntitlementController(EntitlementService service, HolderRefs holders) {
        this.service = service;
        this.holders = holders;
    }

    /** A holder without a journey answers {@code journey_closed}, as in every other call of the journey. */
    @PostMapping
    EntitlementResponse verify(HttpServletRequest request, @AuthenticationPrincipal HolderPrincipal principal) {
        return EntitlementResponse.of(service.check(CurrentTenant.of(request), holders.of(principal)));
    }

    /** The holder says the data of a request is not correct. Answers 204 whether or not the source was told. */
    @PostMapping("/requests/{id}/dispute")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void dispute(HttpServletRequest request, @AuthenticationPrincipal HolderPrincipal principal,
                 @PathVariable("id") String id) {
        if (id.length() > MAX_REQUEST_ID_LENGTH) {
            throw new ProblemException(ProblemCode.BAD_REQUEST);
        }
        service.dispute(CurrentTenant.of(request), holders.of(principal), id);
    }
}
