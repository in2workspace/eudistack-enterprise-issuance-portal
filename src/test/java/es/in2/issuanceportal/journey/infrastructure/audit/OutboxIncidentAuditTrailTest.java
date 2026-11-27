package es.in2.issuanceportal.journey.infrastructure.audit;

import com.eudistack.auditoria.domain.AuditDraft;
import com.eudistack.auditoria.domain.AuditResult;
import es.in2.issuanceportal.journey.application.port.IncidentAuditTrail;
import es.in2.issuanceportal.journey.domain.incident.IncidentType;
import es.in2.issuanceportal.shared.audit.AuditOutbox;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OutboxIncidentAuditTrailTest {

    private static final TenantId SANDBOX = new TenantId("sandbox");

    private final AuditOutbox outbox = mock(AuditOutbox.class);
    private final OutboxIncidentAuditTrail trail = new OutboxIncidentAuditTrail(outbox);

    private static IncidentAuditTrail.Event event(IncidentAuditTrail.Outcome outcome, Optional<String> reason) {
        return new IncidentAuditTrail.Event(UUID.randomUUID(), IncidentType.ACCESS, outcome, reason, Instant.now());
    }

    private AuditDraft systemDraft() {
        ArgumentCaptor<AuditDraft> draft = ArgumentCaptor.forClass(AuditDraft.class);
        verify(outbox).stageAsSystem(eq(SANDBOX), isNull(), draft.capture());
        return draft.getValue();
    }

    @Nested
    @DisplayName("AC-05, EC-05 — an incident sent without a holder")
    class Incident {

        @Test
        void append_sent_stagesItsTypeAsThePortalWithItsOwnResource() {
            // Arrange
            IncidentAuditTrail.Event event = event(IncidentAuditTrail.Outcome.SENT, Optional.empty());

            // Act
            trail.append(SANDBOX, event);

            // Assert
            AuditDraft draft = systemDraft();
            assertThat(draft.fact().eventType()).isEqualTo("SupportIncidentDelivered");
            assertThat(draft.fact().action()).isEqualTo("DeliverIncident");
            assertThat(draft.fact().resource()).isEqualTo("incident:" + event.eventId());
            assertThat(draft.fact().details()).isEqualTo(Map.of("incident_type", "access"));
            assertThat(draft.result()).isEqualTo(AuditResult.SUCCESS);
        }

        @Test
        void append_failed_stagesAFailureWithoutTheMailerReason() {
            // Arrange
            IncidentAuditTrail.Event event = event(IncidentAuditTrail.Outcome.FAILED, Optional.of("smtp_timeout"));

            // Act
            trail.append(SANDBOX, event);

            // Assert
            AuditDraft draft = systemDraft();
            assertThat(draft.result()).isEqualTo(AuditResult.FAILURE);
            assertThat(draft.reason()).isEqualTo("DeliveryFailed");
            assertThat(draft.toString()).doesNotContain("smtp_timeout");
        }

        @Test
        void append_outboxFails_neverReachesTheHolder() {
            // Arrange
            doThrow(new IllegalStateException("outbox unavailable"))
                    .when(outbox).stageAsSystem(any(), any(), any());
            IncidentAuditTrail.Event event = event(IncidentAuditTrail.Outcome.SENT, Optional.empty());

            // Act & Assert
            assertThatNoException().isThrownBy(() -> trail.append(SANDBOX, event));
        }
    }
}
