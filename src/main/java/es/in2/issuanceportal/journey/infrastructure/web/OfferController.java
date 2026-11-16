package es.in2.issuanceportal.journey.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import es.in2.issuanceportal.journey.application.IssuanceService;
import es.in2.issuanceportal.shared.security.HolderPrincipal;
import es.in2.issuanceportal.shared.security.HolderRefs;
import es.in2.issuanceportal.shared.tenant.CurrentTenant;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /journey/offers} of the contract (annex/openapi.yaml): a new offer for a request that has its issuance
 * process. The body only says which request; nothing else is read.
 */
@RestController
@RequestMapping("/api/v1/journey/offers")
class OfferController {

    private final IssuanceService service;
    private final HolderRefs holders;

    OfferController(IssuanceService service, HolderRefs holders) {
        this.service = service;
        this.holders = holders;
    }

    @PostMapping
    OfferResponse newOffer(HttpServletRequest request, @AuthenticationPrincipal HolderPrincipal principal,
                           @RequestBody(required = false) JsonNode body) {
        return OfferResponse.of(service.newOffer(CurrentTenant.of(request), holders.of(principal),
                RequestReferences.requestIdOf(body)));
    }
}
