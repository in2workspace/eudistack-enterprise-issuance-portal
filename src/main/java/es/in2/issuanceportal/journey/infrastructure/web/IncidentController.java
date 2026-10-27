package es.in2.issuanceportal.journey.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import es.in2.issuanceportal.journey.application.IncidentService;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.CurrentTenant;
import es.in2.issuanceportal.shared.tenant.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Optional;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * {@code POST /incidents} of the contract (annex/openapi.yaml). No token or session: it is sent from the
 * home page, before the holder signs in.
 */
@Slf4j
@RestController
class IncidentController {

    static final String RATE_LIMITED_METRIC = "issuance_portal.incidents.rate_limited";

    private final IncidentService service;
    private final IncidentRateLimiter limiter;
    private final MeterRegistry meters;

    IncidentController(IncidentService service, IncidentRateLimiter limiter, MeterRegistry meters) {
        this.service = service;
        this.limiter = limiter;
        this.meters = meters;
    }

    /**
     * The limit is checked once the body is valid: a rejected body does not count. The address is the
     * client's as the platform's proxies saw it, never one the client wrote ({@code ClientAddressFilter}).
     */
    @PostMapping("/api/v1/incidents")
    @ResponseStatus(HttpStatus.ACCEPTED)
    IncidentAccepted send(@Valid @RequestBody IncidentRequest incident, HttpServletRequest request) {
        TenantId tenant = CurrentTenant.of(request);
        Optional<Duration> retryAfter = limiter.tryConsume(request.getRemoteAddr());
        if (retryAfter.isPresent()) {
            rateLimited(tenant, request.getRemoteAddr());
            throw new ProblemException(ProblemCode.TOO_MANY_REQUESTS, retryAfter.get());
        }
        return new IncidentAccepted(service.send(tenant, incident.toIncident()));
    }

    /** A security event (conv-quality-security-gates §14): the client as the limit counts it, never the incident. */
    private void rateLimited(TenantId tenant, String address) {
        log.warn("Incident rate limit exceeded {} {} {}", kv("event", "rate.limit.exceeded"),
                kv("tenant", tenant.value()), kv("client", IncidentRateLimiter.clientKey(address)));
        meters.counter(RATE_LIMITED_METRIC, "tenant", tenant.value()).increment();
    }

    record IncidentAccepted(@JsonProperty("response_time") String responseTime) {
    }
}
