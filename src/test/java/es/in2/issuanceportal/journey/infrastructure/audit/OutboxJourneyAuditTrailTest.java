package es.in2.issuanceportal.journey.infrastructure.audit;

import com.eudistack.auditoria.domain.AuditDraft;
import com.eudistack.auditoria.domain.AuditResult;
import es.in2.issuanceportal.journey.application.port.JourneyAuditTrail;
import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.JourneyStep;
import es.in2.issuanceportal.journey.domain.WithdrawalReason;
import es.in2.issuanceportal.shared.audit.AuditOutbox;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class OutboxJourneyAuditTrailTest {

    private static final TenantId SANDBOX = new TenantId("sandbox");
    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
    private static final UUID PROCESS = UUID.randomUUID();

    private final AuditOutbox outbox = mock(AuditOutbox.class);
    private final OutboxJourneyAuditTrail trail = new OutboxJourneyAuditTrail(outbox);
    private final UUID journeyId = UUID.randomUUID();

    private Journey journey() {
        return Journey.start(journeyId, NOW, "certificate");
    }

    /** The only draft staged as an event of the request. */
    private AuditDraft requestDraft() {
        ArgumentCaptor<AuditDraft> draft = ArgumentCaptor.forClass(AuditDraft.class);
        verify(outbox).stage(eq(journeyId), draft.capture());
        verifyNoMoreInteractions(outbox);
        return draft.getValue();
    }

    /** The only draft staged as the portal's own. */
    private AuditDraft systemDraft() {
        ArgumentCaptor<AuditDraft> draft = ArgumentCaptor.forClass(AuditDraft.class);
        verify(outbox).stageAsSystem(eq(SANDBOX), eq(journeyId), draft.capture());
        verifyNoMoreInteractions(outbox);
        return draft.getValue();
    }

    private static void assertEvent(AuditDraft draft, String eventType, String action, Map<String, Object> details) {
        assertThat(draft.fact().eventType()).isEqualTo(eventType);
        assertThat(draft.fact().action()).isEqualTo(action);
        assertThat(draft.fact().details()).containsExactlyInAnyOrderEntriesOf(details);
    }

    private static void assertOutcome(AuditDraft draft, AuditResult result, String reason) {
        assertThat(draft.result()).isEqualTo(result);
        assertThat(draft.reason()).isEqualTo(reason);
    }

    @Nested
    @DisplayName("AC-01 — changes of a journey")
    class JourneyChanges {

        @Test
        void append_started_stagesTheLoginMethodStepAndState() {
            // Arrange
            JourneyAuditTrail.Event event =
                    JourneyAuditTrail.Event.of(JourneyAuditTrail.Kind.STARTED, journey(), NOW);

            // Act
            trail.append(SANDBOX, event);

            // Assert
            AuditDraft draft = requestDraft();
            assertEvent(draft, "JourneyStarted", "StartJourney",
                    Map.of("identification_method", "certificate", "step", "identified", "state", "in_progress"));
            assertThat(draft.fact().resource()).isEqualTo("journey:" + journeyId);
            assertOutcome(draft, AuditResult.SUCCESS, null);
        }

        @Test
        void append_stepReached_stagesStepAndState() {
            // Arrange
            Journey journey = journey();
            journey.reach(JourneyStep.ENTITLEMENT_CONFIRMED, NOW);

            // Act
            trail.append(SANDBOX, JourneyAuditTrail.Event.of(JourneyAuditTrail.Kind.STEP_REACHED, journey, NOW));

            // Assert
            assertEvent(requestDraft(), "JourneyStepReached", "ReachStep",
                    Map.of("step", "entitlement_confirmed", "state", "in_progress"));
        }

        @Test
        void append_completed_stagesStepAndState() {
            // Arrange
            Journey journey = journey();
            journey.complete(NOW);

            // Act
            trail.append(SANDBOX, JourneyAuditTrail.Event.of(JourneyAuditTrail.Kind.COMPLETED, journey, NOW));

            // Assert
            assertEvent(requestDraft(), "JourneyCompleted", "CompleteJourney",
                    Map.of("step", "offer_obtained", "state", "completed"));
        }

        @ParameterizedTest
        @CsvSource({"CANCELLED, cancelled", "NOT_ENTITLED, not_entitled"})
        void append_withdrawnInARequest_stagesItAsTheRequest(WithdrawalReason reason, String code) {
            // Arrange
            Journey journey = journey();
            journey.withdraw(reason, NOW);

            // Act
            trail.append(SANDBOX, JourneyAuditTrail.Event.of(JourneyAuditTrail.Kind.WITHDRAWN, journey, NOW));

            // Assert
            assertEvent(requestDraft(), "JourneyWithdrawn", "WithdrawJourney",
                    Map.of("withdrawal_reason", code, "step", "identified", "state", "withdrawn"));
        }

        @Test
        void append_withdrawnByExpiry_stagesItAsThePortal() {
            // Arrange
            Journey journey = journey();
            journey.withdraw(WithdrawalReason.EXPIRED, NOW);

            // Act
            trail.append(SANDBOX, JourneyAuditTrail.Event.of(JourneyAuditTrail.Kind.WITHDRAWN, journey, NOW));

            // Assert
            assertThat(systemDraft().fact().details()).containsEntry("withdrawal_reason", "expired");
        }

        @Test
        void append_endedByTheHolder_stagesTheCauseAsTheRequest() {
            // Arrange
            Journey journey = journey();
            journey.complete(NOW);
            journey.end(NOW);

            // Act
            trail.append(SANDBOX, JourneyAuditTrail.Event.ended(journey, JourneyAuditTrail.EndCause.HOLDER, NOW));

            // Assert
            assertEvent(requestDraft(), "JourneyEnded", "EndJourney", Map.of("end_cause", "holder"));
        }

        @Test
        void append_endedByExpiry_stagesItAsThePortal() {
            // Arrange
            Journey journey = journey();
            journey.complete(NOW);
            journey.end(NOW);

            // Act
            trail.append(SANDBOX, JourneyAuditTrail.Event.ended(journey, JourneyAuditTrail.EndCause.EXPIRED, NOW));

            // Assert
            assertEvent(systemDraft(), "JourneyEnded", "EndJourney", Map.of("end_cause", "expired"));
        }
    }

    @Nested
    @DisplayName("AC-01 — requests to other systems")
    class SystemRequests {

        @ParameterizedTest
        @CsvSource(nullValues = "-", value = {
                "ISSUED, SUCCESS, -",
                "REJECTED, DENIED, Rejected",
                "UNAVAILABLE, FAILURE, Unavailable",
                "SYSTEM_TOKEN_UNAVAILABLE, FAILURE, SystemTokenUnavailable",
                "ONBOARDING_FAILED, FAILURE, OnboardingFailed"})
        void append_issuanceRequested_mapsItsResult(JourneyAuditTrail.IssuanceOutcome outcome, AuditResult result,
                                                     String reason) {
            // Arrange
            var event = new JourneyAuditTrail.IssuanceRequested(UUID.randomUUID(), journeyId, "r1", outcome,
                    Optional.of(PROCESS), NOW);

            // Act
            trail.append(SANDBOX, event);

            // Assert
            AuditDraft draft = requestDraft();
            assertEvent(draft, "PortalIssuanceRequested", "RequestIssuance",
                    Map.of("request_id", "r1", "issuance_process_id", PROCESS.toString()));
            assertOutcome(draft, result, reason);
        }

        @Test
        void append_issuanceRequestedWithoutProcess_omitsTheProcess() {
            // Arrange
            var event = new JourneyAuditTrail.IssuanceRequested(UUID.randomUUID(), journeyId, "r1",
                    JourneyAuditTrail.IssuanceOutcome.UNAVAILABLE, Optional.empty(), NOW);

            // Act
            trail.append(SANDBOX, event);

            // Assert
            assertThat(requestDraft().fact().details()).containsOnlyKeys("request_id");
        }

        @ParameterizedTest
        @CsvSource(nullValues = "-", value = {
                "RENEWED, SUCCESS, -",
                "REQUEST_SAVED, DENIED, RequestSaved",
                "REJECTED, DENIED, Rejected",
                "UNAVAILABLE, FAILURE, Unavailable"})
        void append_offerRenewal_mapsItsResult(JourneyAuditTrail.RenewalOutcome outcome, AuditResult result,
                                               String reason) {
            // Arrange
            var event = new JourneyAuditTrail.OfferRenewalRequested(UUID.randomUUID(), journeyId, "r1", PROCESS,
                    outcome, NOW);

            // Act
            trail.append(SANDBOX, event);

            // Assert
            AuditDraft draft = requestDraft();
            assertEvent(draft, "OfferRenewalRequested", "RenewOffer",
                    Map.of("request_id", "r1", "issuance_process_id", PROCESS.toString()));
            assertOutcome(draft, result, reason);
        }

        @ParameterizedTest
        @CsvSource({"HOLDER_DECLARED, holder_declared", "OFFER_NOT_RENEWABLE, offer_not_renewable"})
        void append_requestSaved_stagesItsProcessAndOrigin(JourneyAuditTrail.SavedOrigin origin, String code) {
            // Arrange
            var event = new JourneyAuditTrail.RequestSaved(UUID.randomUUID(), journeyId, "r2", PROCESS, origin, NOW);

            // Act
            trail.append(SANDBOX, event);

            // Assert
            assertEvent(requestDraft(), "IssuanceRequestSaved", "SaveRequest",
                    Map.of("request_id", "r2", "issuance_process_id", PROCESS.toString(), "origin", code));
        }

        @ParameterizedTest
        @CsvSource(nullValues = "-", value = {"CREATED, SUCCESS, -", "FAILED, FAILURE, NotCreated"})
        void append_onboardingTicket_mapsItsResult(JourneyAuditTrail.TicketOutcome outcome, AuditResult result,
                                                   String reason) {
            // Arrange
            var event = new JourneyAuditTrail.OnboardingTicketRequested(UUID.randomUUID(), journeyId, outcome, NOW);

            // Act
            trail.append(SANDBOX, event);

            // Assert
            AuditDraft draft = requestDraft();
            assertEvent(draft, "OnboardingTicketRequested", "RequestOnboardingTicket", Map.of());
            assertOutcome(draft, result, reason);
        }
    }

    @Nested
    @DisplayName("AC-01, AC-05 — verification code")
    class VerificationCode {

        @ParameterizedTest
        @CsvSource(nullValues = "-", value = {
                "SENT, SUCCESS, -",
                "RESEND_TOO_SOON, DENIED, ResendTooSoon",
                "TOO_MANY_CODES, DENIED, TooManyCodes",
                "TOO_MANY_REQUESTS, DENIED, TooManyRequests",
                "EMAIL_UNAVAILABLE, FAILURE, EmailUnavailable"})
        void append_codeRequested_mapsItsResultWithoutDetails(JourneyAuditTrail.EmailCodeOutcome outcome,
                                                              AuditResult result, String reason) {
            // Arrange
            var event = new JourneyAuditTrail.EmailCodeRequested(UUID.randomUUID(), journeyId, outcome, NOW);

            // Act
            trail.append(SANDBOX, event);

            // Assert
            AuditDraft draft = requestDraft();
            assertEvent(draft, "EmailVerificationCodeRequested", "SendVerificationCode", Map.of());
            assertOutcome(draft, result, reason);
        }

        @ParameterizedTest
        @CsvSource(nullValues = "-", value = {
                "VERIFIED, SUCCESS, -",
                "INVALID_CODE, DENIED, InvalidCode",
                "EXPIRED_CODE, DENIED, ExpiredCode",
                "TOO_MANY_ATTEMPTS, DENIED, TooManyAttempts"})
        void append_codeChecked_mapsItsResultWithoutDetails(JourneyAuditTrail.EmailCodeCheck check,
                                                            AuditResult result, String reason) {
            // Arrange
            var event = new JourneyAuditTrail.EmailCodeChecked(UUID.randomUUID(), journeyId, check, NOW);

            // Act
            trail.append(SANDBOX, event);

            // Assert
            AuditDraft draft = requestDraft();
            assertEvent(draft, "EmailVerificationCodeChecked", "CheckVerificationCode", Map.of());
            assertOutcome(draft, result, reason);
        }
    }
}
