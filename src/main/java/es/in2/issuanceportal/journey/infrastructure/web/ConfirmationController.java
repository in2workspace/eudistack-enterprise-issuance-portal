package es.in2.issuanceportal.journey.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import es.in2.issuanceportal.journey.application.IssuanceService;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
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
 * {@code /journey/confirmation} of the contract (annex/openapi.yaml). The body only says which request the holder
 * confirms ({@code RequestReference}): any other field is never read, so neither an email nor any attestation data
 * can come from the interface (FR-05). The email is the one the holder verified with a code.
 */
@RestController
@RequestMapping("/api/v1/journey/confirmation")
class ConfirmationController {

    private static final String REQUEST_ID = "request_id";

    private final IssuanceService service;
    private final HolderRefs holders;

    ConfirmationController(IssuanceService service, HolderRefs holders) {
        this.service = service;
        this.holders = holders;
    }

    /**
     * The body is a {@code RequestReference}: a {@code request_id} string. A missing body, a {@code request_id} that is
     * missing, not a string, blank or of more than 64 characters is {@code bad_request}. Read as a tree so a number is
     * not taken as its text.
     */
    @PostMapping
    OfferResponse confirm(HttpServletRequest request, @AuthenticationPrincipal HolderPrincipal principal,
                          @RequestBody(required = false) JsonNode body) {
        JsonNode requestId = body == null ? null : body.get(REQUEST_ID);
        if (requestId == null || !requestId.isTextual()) {
            throw new ProblemException(ProblemCode.BAD_REQUEST);
        }
        return OfferResponse.of(service.confirm(CurrentTenant.of(request), holders.of(principal), requestId.asText()));
    }
}
