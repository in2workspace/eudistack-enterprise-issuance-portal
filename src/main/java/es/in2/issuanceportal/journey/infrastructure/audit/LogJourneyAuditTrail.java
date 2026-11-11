package es.in2.issuanceportal.journey.infrastructure.audit;

import es.in2.issuanceportal.journey.application.port.JourneyAuditTrail;
import es.in2.issuanceportal.journey.domain.WithdrawalReason;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final String EVENT = "event";
    private static final String JOURNEY_ID = "journey_id";
    private static final String EVENT_ID = "event_id";
    private static final String TENANT = "tenant";
    private static final String RESULT = "result";
    private static final String OCCURRED_AT = "occurred_at";

    private final Logger audit;

    @Autowired
    LogJourneyAuditTrail() {
        this(LoggerFactory.getLogger("audit.journey"));
    }

    LogJourneyAuditTrail(Logger audit) {
        this.audit = audit;
    }

    @Override
    public void append(TenantId tenant, Event event) {
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

    @Override
    public void append(TenantId tenant, IssuanceRequested event) {
        try {
            audit.info("issuance {} {} {} {} {} {} {}",
                    kv(EVENT, "ISSUANCE_REQUESTED"),
                    kv(JOURNEY_ID, event.journeyId()),
                    kv(EVENT_ID, event.eventId()),
                    kv(TENANT, tenant.value()),
                    kv(RESULT, event.result().code()),
                    kv("issuance_process_id", event.issuanceProcessId().orElse(null)),
                    kv(OCCURRED_AT, event.occurredAt()));
        } catch (RuntimeException e) {
            audit.error("Could not record the issuance request {} of journey {}",
                    event.eventId(), event.journeyId(), e);
        }
    }

    @Override
    public void append(TenantId tenant, OnboardingTicketRequested event) {
        try {
            audit.info("onboarding ticket {} {} {} {} {} {}",
                    kv(EVENT, "ONBOARDING_TICKET_REQUESTED"),
                    kv(JOURNEY_ID, event.journeyId()),
                    kv(EVENT_ID, event.eventId()),
                    kv(TENANT, tenant.value()),
                    kv(RESULT, event.result().code()),
                    kv(OCCURRED_AT, event.occurredAt()));
        } catch (RuntimeException e) {
            audit.error("Could not record the onboarding ticket request {} of journey {}",
                    event.eventId(), event.journeyId(), e);
        }
    }

    @Override
    public void append(TenantId tenant, EmailCodeRequested event) {
        try {
            audit.info("email code {} {} {} {} {} {}",
                    kv(EVENT, "EMAIL_CODE_REQUESTED"),
                    kv(JOURNEY_ID, event.journeyId()),
                    kv(EVENT_ID, event.eventId()),
                    kv(TENANT, tenant.value()),
                    kv(RESULT, event.result().code()),
                    kv(OCCURRED_AT, event.occurredAt()));
        } catch (RuntimeException e) {
            audit.error("Could not record the email code request {} of journey {}",
                    event.eventId(), event.journeyId(), e);
        }
    }

    /**
     * After commit the journey has already changed: a failure here must never reach the caller
     * (ES-06), so it is only logged, without personal data.
     */
    private void write(TenantId tenant, Event event) {
        try {
            audit.info("journey {} {} {} {} {} {} {} {}",
                    kv(EVENT, event.kind().name()),
                    kv(JOURNEY_ID, event.journeyId()),
                    kv(EVENT_ID, event.eventId()),
                    kv(TENANT, tenant.value()),
                    kv("state", event.state().code()),
                    kv("step", event.step().code()),
                    kv("withdrawal_reason", event.withdrawalReason().map(WithdrawalReason::code).orElse(null)),
                    kv(OCCURRED_AT, event.occurredAt()));
        } catch (RuntimeException e) {
            audit.error("Could not record the {} event {} of journey {}",
                    event.kind(), event.eventId(), event.journeyId(), e);
        }
    }
}
