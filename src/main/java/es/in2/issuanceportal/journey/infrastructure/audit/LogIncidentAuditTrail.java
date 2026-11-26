package es.in2.issuanceportal.journey.infrastructure.audit;

import es.in2.issuanceportal.journey.application.port.IncidentAuditTrail;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * The trail while the audit publisher is off: each incident event as a structured log line. With the publisher on,
 * {@link OutboxIncidentAuditTrail} replaces it. To be removed once the audit is on in every environment (issue
 * portal-emision-retirar-trazas-auditoria-en-log).
 */
@Component
@ConditionalOnBooleanProperty(name = "auditoria.publisher.enabled", havingValue = false, matchIfMissing = true)
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
