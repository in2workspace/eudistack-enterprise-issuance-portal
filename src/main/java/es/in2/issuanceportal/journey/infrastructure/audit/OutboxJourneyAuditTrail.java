package es.in2.issuanceportal.journey.infrastructure.audit;

import com.eudistack.auditoria.domain.AuditDraft;
import com.eudistack.auditoria.domain.AuditFact;
import es.in2.issuanceportal.journey.application.port.JourneyAuditTrail;
import es.in2.issuanceportal.journey.domain.WithdrawalReason;
import es.in2.issuanceportal.shared.audit.AuditOutbox;
import es.in2.issuanceportal.shared.tenant.TenantId;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import static es.in2.issuanceportal.shared.audit.AuditOutbox.journeyFact;

/**
 * The trail of a journey as events of the audit catalogue (category {@code issuance_portal}), only with the audit
 * publisher on. A failure to stage reaches the caller, which decides whether it undoes its change.
 */
@Component
@ConditionalOnBooleanProperty("auditoria.publisher.enabled")
@RequiredArgsConstructor
class OutboxJourneyAuditTrail implements JourneyAuditTrail {

    private static final String STEP = "step";
    private static final String STATE = "state";
    private static final String REQUEST_ID = "request_id";
    private static final String PROCESS_ID = "issuance_process_id";
    private static final String REJECTED = "Rejected";
    private static final String UNAVAILABLE = "Unavailable";

    private final AuditOutbox outbox;

    /** An expiry is the portal's own doing, also when it is found while serving the holder. */
    @Override
    public void append(TenantId tenant, Event event) {
        AuditDraft draft = AuditDraft.success(factOf(event));
        boolean expiry = event.endCause().filter(cause -> cause == EndCause.EXPIRED).isPresent()
                || event.withdrawalReason().filter(reason -> reason == WithdrawalReason.EXPIRED).isPresent();
        if (expiry) {
            outbox.stageAsSystem(tenant, event.journeyId(), draft);
        } else {
            outbox.stage(event.journeyId(), draft);
        }
    }

    private static AuditFact factOf(Event event) {
        return switch (event.kind()) {
            case STARTED -> progress("JourneyStarted", "StartJourney", event)
                    .detail("identification_method", event.identificationMethod().orElse(null))
                    .build();
            case STEP_REACHED -> progress("JourneyStepReached", "ReachStep", event).build();
            case COMPLETED -> progress("JourneyCompleted", "CompleteJourney", event).build();
            case WITHDRAWN -> progress("JourneyWithdrawn", "WithdrawJourney", event)
                    .detail("withdrawal_reason", event.withdrawalReason().map(WithdrawalReason::code).orElse(null))
                    .build();
            case ENDED -> journeyFact("JourneyEnded", "EndJourney", event.journeyId())
                    .detail("end_cause", event.endCause().map(EndCause::code).orElse(null))
                    .build();
        };
    }

    private static AuditFact.Builder progress(String eventType, String action, Event event) {
        return journeyFact(eventType, action, event.journeyId())
                .detail(STEP, event.step().code())
                .detail(STATE, event.state().code());
    }

    @Override
    public void append(TenantId tenant, IssuanceRequested event) {
        AuditFact fact = journeyFact("PortalIssuanceRequested", "RequestIssuance", event.journeyId())
                .detail(REQUEST_ID, event.requestId())
                .detail(PROCESS_ID, event.issuanceProcessId().orElse(null))
                .build();
        outbox.stage(event.journeyId(), switch (event.result()) {
            case ISSUED -> AuditDraft.success(fact);
            case REJECTED -> AuditDraft.denied(fact, REJECTED);
            case UNAVAILABLE -> AuditDraft.failure(fact, UNAVAILABLE);
            case SYSTEM_TOKEN_UNAVAILABLE -> AuditDraft.failure(fact, "SystemTokenUnavailable");
            case ONBOARDING_FAILED -> AuditDraft.failure(fact, "OnboardingFailed");
        });
    }

    @Override
    public void append(TenantId tenant, OfferRenewalRequested event) {
        AuditFact fact = journeyFact("OfferRenewalRequested", "RenewOffer", event.journeyId())
                .detail(REQUEST_ID, event.requestId())
                .detail(PROCESS_ID, event.issuanceProcessId())
                .build();
        outbox.stage(event.journeyId(), switch (event.result()) {
            case RENEWED -> AuditDraft.success(fact);
            case REQUEST_SAVED -> AuditDraft.denied(fact, "RequestSaved");
            case REJECTED -> AuditDraft.denied(fact, REJECTED);
            case UNAVAILABLE -> AuditDraft.failure(fact, UNAVAILABLE);
        });
    }

    @Override
    public void append(TenantId tenant, RequestSaved event) {
        outbox.stage(event.journeyId(), AuditDraft.success(
                journeyFact("IssuanceRequestSaved", "SaveRequest", event.journeyId())
                        .detail(REQUEST_ID, event.requestId())
                        .detail(PROCESS_ID, event.issuanceProcessId())
                        .detail("origin", event.origin().code())
                        .build()));
    }

    @Override
    public void append(TenantId tenant, OnboardingTicketRequested event) {
        AuditFact fact = journeyFact("OnboardingTicketRequested", "RequestOnboardingTicket", event.journeyId())
                .build();
        outbox.stage(event.journeyId(), switch (event.result()) {
            case CREATED -> AuditDraft.success(fact);
            case FAILED -> AuditDraft.failure(fact, "NotCreated");
        });
    }

    @Override
    public void append(TenantId tenant, EmailCodeRequested event) {
        AuditFact fact = journeyFact("EmailVerificationCodeRequested", "SendVerificationCode", event.journeyId())
                .build();
        outbox.stage(event.journeyId(), switch (event.result()) {
            case SENT -> AuditDraft.success(fact);
            case RESEND_TOO_SOON -> AuditDraft.denied(fact, "ResendTooSoon");
            case TOO_MANY_CODES -> AuditDraft.denied(fact, "TooManyCodes");
            case TOO_MANY_REQUESTS -> AuditDraft.denied(fact, "TooManyRequests");
            case EMAIL_UNAVAILABLE -> AuditDraft.failure(fact, "EmailUnavailable");
        });
    }

    @Override
    public void append(TenantId tenant, EmailCodeChecked event) {
        AuditFact fact = journeyFact("EmailVerificationCodeChecked", "CheckVerificationCode", event.journeyId())
                .build();
        outbox.stage(event.journeyId(), switch (event.result()) {
            case VERIFIED -> AuditDraft.success(fact);
            case INVALID_CODE -> AuditDraft.denied(fact, "InvalidCode");
            case EXPIRED_CODE -> AuditDraft.denied(fact, "ExpiredCode");
            case TOO_MANY_ATTEMPTS -> AuditDraft.denied(fact, "TooManyAttempts");
        });
    }
}
