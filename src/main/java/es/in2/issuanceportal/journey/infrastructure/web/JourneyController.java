package es.in2.issuanceportal.journey.infrastructure.web;

import es.in2.issuanceportal.journey.application.JourneyService;
import es.in2.issuanceportal.session.api.SessionId;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.CurrentTenant;
import es.in2.issuanceportal.shared.web.SessionCookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /journey} of the contract (annex/openapi.yaml). */
@RestController
@RequestMapping("/api/v1/journey")
class JourneyController {

    private final JourneyService service;

    JourneyController(JourneyService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    JourneyResponse start(HttpServletRequest request, HttpServletResponse response) {
        JourneyService.Started started = service.start(CurrentTenant.of(request), SessionCookie.read(request));
        SessionCookie.write(response, started.session());
        return JourneyResponse.of(started.journey());
    }

    @GetMapping
    JourneyResponse current(HttpServletRequest request) {
        return JourneyResponse.of(service.current(CurrentTenant.of(request), session(request)));
    }

    @PostMapping("/withdrawal")
    JourneyResponse withdraw(HttpServletRequest request, HttpServletResponse response) {
        JourneyResponse body = JourneyResponse.of(service.cancel(CurrentTenant.of(request), session(request)));
        SessionCookie.clear(response);
        return body;
    }

    /** No cookie, or one this service never issued, is a journey that no longer exists. */
    private static SessionId session(HttpServletRequest request) {
        return SessionCookie.read(request).orElseThrow(() -> new ProblemException(ProblemCode.JOURNEY_CLOSED));
    }
}
