package es.in2.issuanceportal.shared.audit;

import com.eudistack.auditoria.application.AuditPublisher;
import com.eudistack.auditoria.autoconfigure.AuditoriaProperties;
import com.eudistack.auditoria.context.AuditContexts;
import com.eudistack.auditoria.domain.AuditDraft;
import com.eudistack.auditoria.domain.AuditFact;
import es.in2.issuanceportal.shared.tenant.TenantId;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.util.UUID;

/**
 * Stages the portal's events in the audit outbox, only with the audit publisher on. An event joins the caller's
 * transaction, so the change and its event are kept or undone together; without one it gets a transaction of its
 * own.
 */
@Component
@ConditionalOnBooleanProperty("auditoria.publisher.enabled")
@RequiredArgsConstructor
public class AuditOutbox {

    private final AuditPublisher publisher;
    private final TransactionOperations transactions;
    private final AuditoriaProperties properties;

    /** An event about a journey, whose id is its resource. */
    public static AuditFact.Builder journeyFact(String eventType, String action, UUID journeyId) {
        return AuditFact.builder(eventType).action(action).resource("journey:" + journeyId);
    }

    /** An event the current request causes: its actor, and the journey as the operation it belongs to. */
    public void stage(UUID journeyId, AuditDraft draft) {
        try (var _ = AuditContexts.withCorrelationId(journeyId.toString())) {
            insert(draft);
        }
    }

    /**
     * An event the portal itself causes, with or without a request: the holder of a request is not its actor.
     *
     * @param journeyId the operation it belongs to; {@code null} for an event outside a journey
     */
    public void stageAsSystem(TenantId tenant, UUID journeyId, AuditDraft draft) {
        String correlationId = journeyId == null ? null : journeyId.toString();
        try (var _ = AuditContexts.system(tenant.value(), properties.getSource(), correlationId)) {
            insert(draft);
        }
    }

    private void insert(AuditDraft draft) {
        transactions.executeWithoutResult(status -> publisher.stage(draft));
    }
}
