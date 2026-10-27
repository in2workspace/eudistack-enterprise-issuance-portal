package es.in2.issuanceportal.journey.application;

import es.in2.issuanceportal.journey.application.port.IncidentAuditTrail;
import es.in2.issuanceportal.journey.application.port.IncidentAuditTrail.Outcome;
import es.in2.issuanceportal.journey.application.port.IncidentDeliveryException;
import es.in2.issuanceportal.journey.application.port.IncidentMailer;
import es.in2.issuanceportal.journey.application.port.IncidentMessage;
import es.in2.issuanceportal.journey.domain.config.Support;
import es.in2.issuanceportal.journey.domain.incident.Incident;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.TenantId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Sends a holder's incident to the tenant's support (prd.md §3.5, design.md AD-8). Nothing is stored:
 * the incident only lives for the time it takes to deliver it. Every delivery attempt leaves a trace,
 * without its content (prd.md FR-13).
 */
@Slf4j
@Service
public class IncidentService {

    private final PortalConfigService configs;
    private final IncidentMailer mailer;
    private final IncidentAuditTrail trail;
    private final Clock clock;

    public IncidentService(PortalConfigService configs, IncidentMailer mailer, IncidentAuditTrail trail, Clock clock) {
        this.configs = configs;
        this.mailer = mailer;
        this.trail = trail;
        this.clock = clock;
    }

    /** @return the tenant's support response time, as shown to the holder */
    public String send(TenantId tenant, Incident incident) {
        Support support = configs.current(tenant).support();
        if (support.email() == null || support.responseTime() == null) {
            log.warn("Incident of tenant {} not sent: its portal configuration has no support email or response time",
                    tenant);
            throw new ProblemException(ProblemCode.SERVICE_UNAVAILABLE);
        }
        try {
            mailer.send(IncidentMessage.of(incident, support.email()));
        } catch (IncidentDeliveryException e) {
            log.warn("Incident of tenant {} not sent: {}", tenant, e.getMessage());
            trace(tenant, incident, Outcome.FAILED, Optional.of(e.getMessage()));
            throw new ProblemException(ProblemCode.SERVICE_UNAVAILABLE);
        }
        trace(tenant, incident, Outcome.SENT, Optional.empty());
        return support.responseTime();
    }

    private void trace(TenantId tenant, Incident incident, Outcome outcome, Optional<String> reason) {
        trail.append(tenant, new IncidentAuditTrail.Event(UUID.randomUUID(), incident.type(), outcome, reason,
                clock.instant()));
    }
}
