package es.in2.issuanceportal.authenticsource.infrastructure.audit;

import com.eudistack.auditoria.domain.AuditDraft;
import com.eudistack.auditoria.domain.AuditResult;
import es.in2.issuanceportal.authenticsource.application.port.AuthenticSourceAuditTrail;
import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import es.in2.issuanceportal.authenticsource.domain.NotConfirmedReason;
import es.in2.issuanceportal.authenticsource.domain.RequestStatus;
import es.in2.issuanceportal.shared.audit.AuditOutbox;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OutboxAuthenticSourceAuditTrailTest {

    private static final TenantId CGCOM = new TenantId("cgcom");

    private final AuditOutbox outbox = mock(AuditOutbox.class);
    private final OutboxAuthenticSourceAuditTrail trail = new OutboxAuthenticSourceAuditTrail(outbox);
    private final UUID journeyId = UUID.randomUUID();

    private AuditDraft draft() {
        ArgumentCaptor<AuditDraft> draft = ArgumentCaptor.forClass(AuditDraft.class);
        verify(outbox).stage(eq(journeyId), draft.capture());
        return draft.getValue();
    }

    private AuthenticSourceAuditTrail.Event check(AuthenticSourceAuditTrail.Result result,
                                                  NotConfirmedReason reason) {
        return new AuthenticSourceAuditTrail.Event(UUID.randomUUID(), journeyId, "doctorid.sd.1", "http",
                IdentifierType.COLEGIADO, result, Optional.ofNullable(reason),
                Map.of(RequestStatus.AVAILABLE, 2), Duration.ofMillis(120), Instant.now());
    }

    private AuthenticSourceAuditTrail.DisputeEvent dispute(String attestationType,
                                                           AuthenticSourceAuditTrail.DisputeResult result) {
        return new AuthenticSourceAuditTrail.DisputeEvent(UUID.randomUUID(), journeyId, "r1", attestationType,
                "none", result, Duration.ofMillis(8), Instant.now());
    }

    @Nested
    @DisplayName("AC-01, AC-05 — entitlement check")
    class Check {

        @Test
        void append_confirmed_stagesOnlyTheFieldsOfTheCatalogue() {
            // Arrange
            AuthenticSourceAuditTrail.Event event = check(AuthenticSourceAuditTrail.Result.CONFIRMED, null);

            // Act
            trail.append(CGCOM, event);

            // Assert
            AuditDraft draft = draft();
            assertThat(draft.fact().eventType()).isEqualTo("EntitlementChecked");
            assertThat(draft.fact().action()).isEqualTo("CheckEntitlement");
            assertThat(draft.fact().resource()).isEqualTo("journey:" + journeyId);
            assertThat(draft.fact().details()).isEqualTo(Map.of("attestation_type", "doctorid.sd.1",
                    "adapter", "http", "identifier_type", "colegiado", "duration_ms", 120L));
            assertThat(draft.result()).isEqualTo(AuditResult.SUCCESS);
        }

        @ParameterizedTest
        @CsvSource(nullValues = "-", value = {
                "NOT_CONFIRMED, NOT_FOUND, DENIED, NotFound",
                "NOT_CONFIRMED, NO_REQUESTS, DENIED, NoRequests",
                "NOT_CONFIRMED, -, DENIED, NoAvailableRequests",
                "UNAVAILABLE, -, FAILURE, Unavailable"})
        void append_notConfirmed_mapsItsReason(AuthenticSourceAuditTrail.Result result, NotConfirmedReason reason,
                                               AuditResult expected, String expectedReason) {
            // Arrange
            AuthenticSourceAuditTrail.Event event = check(result, reason);

            // Act
            trail.append(CGCOM, event);

            // Assert
            AuditDraft draft = draft();
            assertThat(draft.result()).isEqualTo(expected);
            assertThat(draft.reason()).isEqualTo(expectedReason);
        }
    }

    @Nested
    @DisplayName("AC-01 — notice of data not correct")
    class Dispute {

        @ParameterizedTest
        @CsvSource(nullValues = "-", value = {
                "SENT, sent, SUCCESS, -",
                "NOT_SUPPORTED, not_supported, SUCCESS, -",
                "FAILED, failed, FAILURE, DeliveryFailed"})
        void appendDispute_anyNotice_mapsItsResult(AuthenticSourceAuditTrail.DisputeResult notice, String code,
                                                   AuditResult expected, String reason) {
            // Arrange
            AuthenticSourceAuditTrail.DisputeEvent event = dispute("doctorid.sd.1", notice);

            // Act
            trail.appendDispute(CGCOM, event);

            // Assert
            AuditDraft draft = draft();
            assertThat(draft.fact().eventType()).isEqualTo("RequestDataDisputed");
            assertThat(draft.fact().action()).isEqualTo("DisputeRequestData");
            assertThat(draft.fact().details()).isEqualTo(Map.of("request_id", "r1",
                    "attestation_type", "doctorid.sd.1", "adapter", "none", "notice", code, "duration_ms", 8L));
            assertThat(draft.result()).isEqualTo(expected);
            assertThat(draft.reason()).isEqualTo(reason);
        }

        @Test
        void appendDispute_unknownAttestationType_omitsIt() {
            // Arrange
            AuthenticSourceAuditTrail.DisputeEvent event =
                    dispute(null, AuthenticSourceAuditTrail.DisputeResult.FAILED);

            // Act
            trail.appendDispute(CGCOM, event);

            // Assert
            assertThat(draft().fact().details()).doesNotContainKey("attestation_type");
        }
    }
}
