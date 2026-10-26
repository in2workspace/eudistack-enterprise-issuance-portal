package es.in2.issuanceportal.journey.infrastructure.audit;

import es.in2.issuanceportal.journey.application.port.IncidentAuditTrail;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Provisional trail: each incident event as a structured log line.
 *
 * <p>TODO(EUD-276): send the events to the audit API (EUD-244) instead, together with the journey
 * events of {@link LogJourneyAuditTrail}. Recorded in the feature's tech-debt.md.
 */
@Component
class LogIncidentAuditTrail implements IncidentAuditTrail {

    private final Logger audit;

    @Autowired
    LogIncidentAuditTrail() {
        this(LoggerFactory.getLogger("audit.incident"));
    }

    LogIncidentAuditTrail(Logger audit) {
        this.audit = audit;
    }

    /** The holder already has the answer: a failure here never reaches them, it is only logged. */
    @Override
    public void append(TenantId tenant, Event event) {
        try {
            audit.info("incident {} {} {} {} {} {}",
                    kv("event", "INCIDENT_" + event.outcome().name()),
                    kv("event_id", event.eventId()),
                    kv("tenant", tenant.value()),
                    kv("incident_type", event.type().code()),
                    kv("reason", event.reason().orElse(null)),
                    kv("occurred_at", event.occurredAt()));
        } catch (RuntimeException e) {
            audit.error("Could not record the incident event {}", event.eventId(), e);
        }
    }
}
