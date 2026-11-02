package es.in2.issuanceportal.journey.infrastructure.web;

import es.in2.issuanceportal.journey.application.PortalConfigService;
import es.in2.issuanceportal.shared.tenant.CurrentTenant;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /config} of the contract (annex/openapi.yaml). Public: no token, no session. */
@RestController
class PortalConfigController {

    private final PortalConfigService service;

    PortalConfigController(PortalConfigService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/config")
    PortalConfigResponse config(HttpServletRequest request) {
        return PortalConfigResponse.of(service.current(CurrentTenant.of(request)));
    }
}
