package es.in2.issuanceportal.journey.infrastructure.audit;

import es.in2.issuanceportal.journey.application.port.JourneyAuditTrail;
import es.in2.issuanceportal.journey.domain.WithdrawalReason;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Provisional trail: each event as a structured log line, written once the change is committed.
 *
 * <p>TODO(EUD-275): send the events to the audit API (EUD-244) instead. That needs journey event
 * types in the audit catalogue, the system token of the portal (EUD-277) and the producer outbox
 * (DT-002). Recorded in the feature's tech-debt.md.
 */
@Component
class LogJourneyAuditTrail implements JourneyAuditTrail {

    private static final Logger AUDIT = LoggerFactory.getLogger("audit.journey");

    @Override
    public void record(TenantId tenant, Event event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    write(tenant, event);
                }
            });
        } else {
            write(tenant, event);
        }
    }

    private static void write(TenantId tenant, Event event) {
        AUDIT.info("journey {} {}",
                kv("event", event.kind().name()),
                kv("journey_id", event.journeyId()),
                kv("event_id", event.eventId()),
                kv("tenant", tenant.value()),
                kv("state", event.state().code()),
                kv("step", event.step().code()),
                kv("withdrawal_reason", event.withdrawalReason().map(WithdrawalReason::code).orElse(null)),
                kv("occurred_at", event.occurredAt()));
    }
}
