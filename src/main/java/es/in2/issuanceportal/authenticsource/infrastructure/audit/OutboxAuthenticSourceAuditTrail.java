package es.in2.issuanceportal.authenticsource.infrastructure.audit;

import com.eudistack.auditoria.domain.AuditDraft;
import com.eudistack.auditoria.domain.AuditFact;
import es.in2.issuanceportal.authenticsource.application.port.AuthenticSourceAuditTrail;
import es.in2.issuanceportal.authenticsource.domain.NotConfirmedReason;
import es.in2.issuanceportal.shared.audit.AuditOutbox;
import es.in2.issuanceportal.shared.tenant.TenantId;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static es.in2.issuanceportal.shared.audit.AuditOutbox.journeyFact;

/**
 * Checks and notices as events of the audit catalogue (category {@code issuance_portal}), only with the audit
 * publisher on. A failure to stage reaches the caller, which decides whether it undoes what it writes.
 */
@Component
@ConditionalOnBooleanProperty("auditoria.publisher.enabled")
@RequiredArgsConstructor
class OutboxAuthenticSourceAuditTrail implements AuthenticSourceAuditTrail {

    private static final String ATTESTATION_TYPE = "attestation_type";
    private static final String ADAPTER = "adapter";
    private static final String DURATION_MS = "duration_ms";

    private final AuditOutbox outbox;

    @Override
    public void append(TenantId tenant, Event event) {
        AuditFact fact = journeyFact("EntitlementChecked", "CheckEntitlement", event.journeyId())
                .detail(ATTESTATION_TYPE, event.attestationType())
                .detail(ADAPTER, event.adapter())
                .detail("identifier_type", event.identifierType().code())
                .detail(DURATION_MS, event.duration().toMillis())
                .build();
        outbox.stage(event.journeyId(), switch (event.result()) {
            case CONFIRMED -> AuditDraft.success(fact);
            case NOT_CONFIRMED -> AuditDraft.denied(fact, reasonOf(event.reason()));
            case UNAVAILABLE -> AuditDraft.failure(fact, "Unavailable");
        });
    }

    /** Without a reason the holder has requests and none is available. */
    private static String reasonOf(Optional<NotConfirmedReason> reason) {
        return reason.map(known -> switch (known) {
            case NOT_FOUND -> "NotFound";
            case NO_REQUESTS -> "NoRequests";
        }).orElse("NoAvailableRequests");
    }

    @Override
    public void appendDispute(TenantId tenant, DisputeEvent event) {
        AuditFact fact = journeyFact("RequestDataDisputed", "DisputeRequestData", event.journeyId())
                .detail("request_id", event.requestId())
                .detail(ATTESTATION_TYPE, event.attestationType())
                .detail(ADAPTER, event.adapter())
                .detail("notice", event.result().code())
                .detail(DURATION_MS, event.duration().toMillis())
                .build();
        outbox.stage(event.journeyId(), switch (event.result()) {
            case SENT, NOT_SUPPORTED -> AuditDraft.success(fact);
            case FAILED -> AuditDraft.failure(fact, "DeliveryFailed");
        });
    }
}
