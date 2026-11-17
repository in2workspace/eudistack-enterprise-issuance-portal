package es.in2.issuanceportal.journey.infrastructure.web;

import es.in2.issuanceportal.journey.application.JourneyService;
import es.in2.issuanceportal.journey.application.JourneyViewService;
import es.in2.issuanceportal.session.api.HolderIdentity;
import es.in2.issuanceportal.session.api.HolderRef;
import es.in2.issuanceportal.session.api.PersonalIdentifier;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.security.HolderPrincipal;
import es.in2.issuanceportal.shared.security.HolderRefs;
import es.in2.issuanceportal.shared.tenant.CurrentTenant;
import es.in2.issuanceportal.shared.tenant.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /journey} of the contract (annex/openapi.yaml). The holder is the one of the validated Access Token: there
 * is no session of the browser, and every call finds the holder's journey by their reference (design.md §3.2).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/journey")
class JourneyController {

    /** {@code identification_method} of a journey opened with a qualified certificate. */
    static final String CERTIFICATE = "certificate";
    /** {@code identification_method} of a journey opened with an attestation (DoctorID). */
    static final String ATTESTATION = "attestation";

    private final JourneyService service;
    private final JourneyViewService views;
    private final HolderRefs holders;

    JourneyController(JourneyService service, JourneyViewService views, HolderRefs holders) {
        this.service = service;
        this.views = views;
        this.holders = holders;
    }

    /** 201 with the journey it opened, or 200 with the one the holder already had, as {@code GET} shows it. */
    @PostMapping
    ResponseEntity<JourneyResponse> open(HttpServletRequest request, @AuthenticationPrincipal HolderPrincipal principal) {
        TenantId tenant = CurrentTenant.of(request);
        HolderIdentity identity = identityOf(tenant, principal);
        HolderRef holder = holders.of(principal);
        JourneyService.Opened opened = service.open(tenant, holder, identity, identificationMethodOf(principal));
        if (opened.created()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(JourneyResponse.of(opened.journey()));
        }
        return ResponseEntity.ok(JourneyResponse.of(views.current(tenant, holder)));
    }

    @GetMapping
    JourneyResponse current(HttpServletRequest request, @AuthenticationPrincipal HolderPrincipal principal) {
        return JourneyResponse.of(views.current(CurrentTenant.of(request), holders.of(principal)));
    }

    @PostMapping("/withdrawal")
    JourneyResponse withdraw(HttpServletRequest request, @AuthenticationPrincipal HolderPrincipal principal) {
        return JourneyResponse.of(service.cancel(CurrentTenant.of(request), holders.of(principal)));
    }

    /**
     * The identity the journey is opened with. A valid token without it cannot open one (ac.md decision 6, ES-03): the
     * warning names the tenant and never the holder.
     */
    private static HolderIdentity identityOf(TenantId tenant, HolderPrincipal principal) {
        if (isBlank(principal.givenName()) || isBlank(principal.familyName())
                || isBlank(principal.personalAdministrativeNumber())) {
            log.warn("A valid token of tenant {} lacks given_name, family_name or personal_administrative_number:"
                    + " no journey opened", tenant);
            throw new ProblemException(ProblemCode.UNAUTHENTICATED);
        }
        return new HolderIdentity(principal.givenName(), principal.familyName(),
                new PersonalIdentifier(principal.personalIdentifierType(), principal.personalAdministrativeNumber()),
                null);
    }

    private static String identificationMethodOf(HolderPrincipal principal) {
        return isBlank(principal.credentialType()) ? CERTIFICATE : ATTESTATION;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
