package es.in2.issuanceportal.authenticsource.infrastructure.audit;

import es.in2.issuanceportal.authenticsource.application.port.AuthenticSourceAuditTrail;
import es.in2.issuanceportal.authenticsource.domain.NotConfirmedReason;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Provisional trail: each check as a structured log line, like the journey's trail.
 *
 * <p>TODO(EUD-275): send the events to the audit API (EUD-244) instead, together with the journey's.
 */
@Component
class LogAuthenticSourceAuditTrail implements AuthenticSourceAuditTrail {

    private final Logger audit;

    @Autowired
    LogAuthenticSourceAuditTrail() {
        this(LoggerFactory.getLogger("audit.authentic_source"));
    }

    LogAuthenticSourceAuditTrail(Logger audit) {
        this.audit = audit;
    }

    /** The trail never decides the outcome of a check: a failure is only logged, without personal data. */
    @Override
    public void append(TenantId tenant, Event event) {
        try {
            audit.info("entitlement check {} {} {} {} {} {} {} {} {} {}",
                    kv("journey_id", event.journeyId()),
                    kv("event_id", event.eventId()),
                    kv("tenant", tenant.value()),
                    kv("attestation_type", event.attestationType()),
                    kv("adapter", event.adapter()),
                    kv("identifier_type", event.identifierType().code()),
                    kv("result", event.result().code()),
                    kv("reason", event.reason().map(NotConfirmedReason::code).orElse(null)),
                    kv("duration_ms", event.duration().toMillis()),
                    kv("occurred_at", event.occurredAt()));
        } catch (RuntimeException e) {
            audit.error("Could not record the entitlement check {} of journey {}", event.eventId(),
                    event.journeyId(), e);
        }
    }
}
