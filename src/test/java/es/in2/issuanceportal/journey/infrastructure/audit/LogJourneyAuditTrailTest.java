package es.in2.issuanceportal.journey.infrastructure.audit;

import es.in2.issuanceportal.journey.application.port.JourneyAuditTrail;
import es.in2.issuanceportal.journey.domain.JourneyState;
import es.in2.issuanceportal.journey.domain.JourneyStep;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LogJourneyAuditTrailTest {

    private static final TenantId SANDBOX = new TenantId("sandbox");

    private final Logger audit = mock(Logger.class);
    private final LogJourneyAuditTrail trail = new LogJourneyAuditTrail(audit);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void append_insideTransaction_writesOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        trail.append(SANDBOX, event());
        verify(audit, never()).info(anyString(), any(Object[].class));

        TransactionSynchronizationUtils.triggerAfterCommit();
        verify(audit).info(anyString(), any(Object[].class));
    }

    @Test
    void append_writeFailsAfterCommit_doesNotThrow() {
        doThrow(new IllegalStateException("log sink down")).when(audit).info(anyString(), any(Object[].class));
        TransactionSynchronizationManager.initSynchronization();
        trail.append(SANDBOX, event());

        assertThatNoException().isThrownBy(TransactionSynchronizationUtils::triggerAfterCommit);
    }

    @Test
    void append_writeFailsOutsideTransaction_doesNotThrow() {
        doThrow(new IllegalStateException("log sink down")).when(audit).info(anyString(), any(Object[].class));

        assertThatNoException().isThrownBy(() -> trail.append(SANDBOX, event()));
    }

    @Test
    void append_issuanceRequest_writesResultAndProcessAtOnce() {
        UUID process = UUID.randomUUID();

        trail.append(SANDBOX, new JourneyAuditTrail.IssuanceRequested(UUID.randomUUID(), UUID.randomUUID(),
                JourneyAuditTrail.IssuanceOutcome.ISSUED, Optional.of(process), Instant.now()));

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(audit).info(anyString(), arguments.capture());
        assertThat(java.util.Arrays.stream(arguments.getValue()).map(Object::toString))
                .contains("result=issued", "issuance_process_id=" + process, "event=ISSUANCE_REQUESTED",
                        "tenant=sandbox");
    }

    @Test
    void append_issuanceRequestWriteFails_doesNotThrow() {
        doThrow(new IllegalStateException("log sink down")).when(audit).info(anyString(), any(Object[].class));

        assertThatNoException().isThrownBy(() -> trail.append(SANDBOX, new JourneyAuditTrail.IssuanceRequested(
                UUID.randomUUID(), UUID.randomUUID(), JourneyAuditTrail.IssuanceOutcome.UNAVAILABLE,
                Optional.empty(), Instant.now())));
    }

    @Test
    void append_onboardingTicketRequest_writesTheResultOnly() {
        trail.append(SANDBOX, new JourneyAuditTrail.OnboardingTicketRequested(UUID.randomUUID(), UUID.randomUUID(),
                JourneyAuditTrail.TicketOutcome.FAILED, Instant.now()));

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(audit).info(anyString(), arguments.capture());
        assertThat(java.util.Arrays.stream(arguments.getValue()).map(Object::toString))
                .contains("result=failed", "event=ONBOARDING_TICKET_REQUESTED", "tenant=sandbox");
    }

    @Test
    void append_onboardingTicketRequestWriteFails_doesNotThrow() {
        doThrow(new IllegalStateException("log sink down")).when(audit).info(anyString(), any(Object[].class));

        assertThatNoException().isThrownBy(() -> trail.append(SANDBOX, new JourneyAuditTrail.OnboardingTicketRequested(
                UUID.randomUUID(), UUID.randomUUID(), JourneyAuditTrail.TicketOutcome.CREATED, Instant.now())));
    }

    @Test
    void append_emailCodeRequest_writesTheResultOnly() {
        trail.append(SANDBOX, new JourneyAuditTrail.EmailCodeRequested(UUID.randomUUID(), UUID.randomUUID(),
                JourneyAuditTrail.EmailCodeOutcome.RESEND_TOO_SOON, Instant.now()));

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(audit).info(anyString(), arguments.capture());
        assertThat(java.util.Arrays.stream(arguments.getValue()).map(Object::toString))
                .contains("result=resend_too_soon", "event=EMAIL_CODE_REQUESTED", "tenant=sandbox");
    }

    @Test
    void append_emailCodeRequestWriteFails_doesNotThrow() {
        doThrow(new IllegalStateException("log sink down")).when(audit).info(anyString(), any(Object[].class));

        assertThatNoException().isThrownBy(() -> trail.append(SANDBOX, new JourneyAuditTrail.EmailCodeRequested(
                UUID.randomUUID(), UUID.randomUUID(), JourneyAuditTrail.EmailCodeOutcome.SENT, Instant.now())));
    }

    private static JourneyAuditTrail.Event event() {
        return new JourneyAuditTrail.Event(UUID.randomUUID(), JourneyAuditTrail.Kind.STARTED, UUID.randomUUID(),
                JourneyState.IN_PROGRESS, JourneyStep.STARTED, Optional.empty(), Instant.now());
    }
}
