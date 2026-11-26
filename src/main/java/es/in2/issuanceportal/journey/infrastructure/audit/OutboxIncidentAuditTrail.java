package es.in2.issuanceportal.journey.infrastructure.audit;

import com.eudistack.auditoria.domain.AuditDraft;
import com.eudistack.auditoria.domain.AuditFact;
import es.in2.issuanceportal.journey.application.port.IncidentAuditTrail;
import es.in2.issuanceportal.shared.audit.AuditOutbox;
import es.in2.issuanceportal.shared.tenant.TenantId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

/**
 * Each incident event as an event of the audit catalogue, only with the audit publisher on. An incident is sent
 * before the holder signs in and belongs to no journey, so the portal is its actor.
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty("auditoria.publisher.enabled")
@RequiredArgsConstructor
class OutboxIncidentAuditTrail implements IncidentAuditTrail {

    private final AuditOutbox outbox;

    /** The holder already has the answer: a failure here never reaches them, it is only logged. */
    @Override
    public void append(TenantId tenant, Event event) {
        try {
            AuditFact fact = AuditFact.builder("SupportIncidentDelivered")
                    .action("DeliverIncident")
                    .resource("incident:" + event.eventId())
                    .detail("incident_type", event.type().code())
                    .build();
            outbox.stageAsSystem(tenant, null, switch (event.outcome()) {
                case SENT -> AuditDraft.success(fact);
                case FAILED -> AuditDraft.failure(fact, "DeliveryFailed");
            });
        } catch (RuntimeException e) {
            log.error("Could not stage the incident event {}", event.eventId(), e);
        }
    }
}
