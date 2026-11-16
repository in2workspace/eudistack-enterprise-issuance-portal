package es.in2.issuanceportal.journey.infrastructure.web;

import es.in2.issuanceportal.journey.application.IssuanceService;
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
 * {@code /journey/requests/{request_id}/saved} of the contract (annex/openapi.yaml): the holder declares the
 * attestation of a request saved. No body; the service never asks the Issuer.
 */
@RestController
@RequestMapping("/api/v1/journey/requests")
class SavedController {

    private final IssuanceService service;
    private final HolderRefs holders;

    SavedController(IssuanceService service, HolderRefs holders) {
        this.service = service;
        this.holders = holders;
    }

    @PostMapping("/{requestId}/saved")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void declareSaved(HttpServletRequest request, @AuthenticationPrincipal HolderPrincipal principal,
                      @PathVariable("requestId") String requestId) {
        service.declareSaved(CurrentTenant.of(request), holders.of(principal), requestId);
    }
}
