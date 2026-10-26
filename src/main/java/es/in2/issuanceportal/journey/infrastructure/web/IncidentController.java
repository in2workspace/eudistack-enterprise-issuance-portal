package es.in2.issuanceportal.journey.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import es.in2.issuanceportal.journey.application.IncidentService;
import es.in2.issuanceportal.shared.tenant.CurrentTenant;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /incidents} of the contract (annex/openapi.yaml). No token or session: it is sent from the
 * home page, before the holder signs in.
 */
@RestController
class IncidentController {

    private final IncidentService service;

    IncidentController(IncidentService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/incidents")
    @ResponseStatus(HttpStatus.ACCEPTED)
    IncidentAccepted send(@Valid @RequestBody IncidentRequest incident, HttpServletRequest request) {
        return new IncidentAccepted(service.send(CurrentTenant.of(request), incident.toIncident()));
    }

    record IncidentAccepted(@JsonProperty("response_time") String responseTime) {
    }
}
