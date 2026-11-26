package es.in2.issuanceportal.shared.audit;

import com.eudistack.auditoria.application.AuditPublisher;
import com.eudistack.auditoria.autoconfigure.AuditoriaProperties;
import com.eudistack.auditoria.context.AuditContextScope;
import com.eudistack.auditoria.context.AuditContexts;
import com.eudistack.auditoria.context.ThreadLocalAuditContextAccessor;
import com.eudistack.auditoria.domain.AuditActor;
import com.eudistack.auditoria.domain.AuditContext;
import com.eudistack.auditoria.domain.AuditDraft;
import com.eudistack.auditoria.domain.AuditFact;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditOutboxTest {

    private static final TenantId SANDBOX = new TenantId("sandbox");
    private static final UUID JOURNEY = UUID.randomUUID();
    private static final AuditContext HOLDER_REQUEST =
            new AuditContext("sandbox", null, "request-correlation", AuditActor.holder("holder-ref"));

    private final ThreadLocalAuditContextAccessor contexts = new ThreadLocalAuditContextAccessor();
    private final List<AuditContext> staged = new ArrayList<>();
    private final AuditDraft draft = AuditDraft.success(
            AuditOutbox.journeyFact("JourneyStepReached", "ReachStep", JOURNEY).build());
    private AuditoriaProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AuditoriaProperties();
        properties.setSource("issuance-portal");
    }

    private AuditOutbox outbox(AuditPublisher publisher) {
        return new AuditOutbox(publisher, TransactionOperations.withoutTransaction(), properties);
    }

    private AuditOutbox recordingOutbox() {
        return outbox(ignored -> staged.add(contexts.current().orElseThrow()));
    }

    @Nested
    @DisplayName("An event of a request")
    class RequestEvent {

        @Test
        void stage_holderRequest_keepsTheActorAndCorrelatesByJourney() {
            // Arrange
            AuditOutbox outbox = recordingOutbox();

            // Act
            try (AuditContextScope _ = AuditContexts.open(HOLDER_REQUEST)) {
                outbox.stage(JOURNEY, draft);
            }

            // Assert
            assertThat(staged).containsExactly(HOLDER_REQUEST.withCorrelationId(JOURNEY.toString()));
        }

        @Test
        void stage_publisherFails_propagatesAndLeavesTheRequestContext() {
            // Arrange
            AuditOutbox outbox = outbox(ignored -> {
                throw new IllegalStateException("outbox unavailable");
            });

            try (AuditContextScope _ = AuditContexts.open(HOLDER_REQUEST)) {
                // Act & Assert
                assertThatThrownBy(() -> outbox.stage(JOURNEY, draft))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("outbox unavailable");
                assertThat(contexts.current()).contains(HOLDER_REQUEST);
            }
        }
    }

    @Nested
    @DisplayName("EC-01, EC-05 — an event of the portal itself")
    class SystemEvent {

        @Test
        void stageAsSystem_insideAHolderRequest_usesTheSystemActorAndRestoresTheHolder() {
            // Arrange
            AuditOutbox outbox = recordingOutbox();

            try (AuditContextScope _ = AuditContexts.open(HOLDER_REQUEST)) {
                // Act
                outbox.stageAsSystem(SANDBOX, JOURNEY, draft);

                // Assert
                assertThat(staged).containsExactly(new AuditContext("sandbox", null, JOURNEY.toString(),
                        AuditActor.system("issuance-portal")));
                assertThat(contexts.current()).contains(HOLDER_REQUEST);
            }
        }

        @Test
        void stageAsSystem_withoutRequestNorJourney_generatesACorrelation() {
            // Arrange
            AuditOutbox outbox = recordingOutbox();

            // Act
            outbox.stageAsSystem(SANDBOX, null, draft);

            // Assert
            assertThat(staged).singleElement().satisfies(context -> {
                assertThat(context.actor()).isEqualTo(AuditActor.system("issuance-portal"));
                assertThat(context.correlationId()).isNotBlank();
            });
            assertThat(contexts.current()).isEmpty();
        }
    }

    @Nested
    @DisplayName("journeyFact")
    class JourneyFact {

        @Test
        void journeyFact_anyEvent_hasTheJourneyAsResource() {
            // Arrange
            UUID journey = UUID.randomUUID();

            // Act
            AuditFact fact = AuditOutbox.journeyFact("JourneyCompleted", "CompleteJourney", journey).build();

            // Assert
            assertThat(fact.resource()).isEqualTo("journey:" + journey);
        }
    }
}
