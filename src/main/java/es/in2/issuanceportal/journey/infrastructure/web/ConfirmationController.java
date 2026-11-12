package es.in2.issuanceportal.journey.infrastructure.web;

import es.in2.issuanceportal.journey.application.IssuanceService;
import es.in2.issuanceportal.shared.security.HolderPrincipal;
import es.in2.issuanceportal.shared.security.HolderRefs;
import es.in2.issuanceportal.shared.tenant.CurrentTenant;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    private final HolderRefs holders;

    ConfirmationController(IssuanceService service, HolderRefs holders) {
        this.service = service;
        this.holders = holders;
    }

    @PostMapping
    OfferResponse confirm(HttpServletRequest request, @AuthenticationPrincipal HolderPrincipal principal) {
        return OfferResponse.of(service.confirm(CurrentTenant.of(request), holders.of(principal)));
    }
}
