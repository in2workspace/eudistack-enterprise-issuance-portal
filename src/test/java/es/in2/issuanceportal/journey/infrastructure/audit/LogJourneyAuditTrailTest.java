package es.in2.issuanceportal.journey.infrastructure.audit;

import es.in2.issuanceportal.journey.application.port.JourneyAuditTrail;
import es.in2.issuanceportal.journey.domain.JourneyState;
import es.in2.issuanceportal.journey.domain.JourneyStep;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

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
    void record_insideTransaction_writesOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        trail.record(SANDBOX, event());
        verify(audit, never()).info(anyString(), any(Object[].class));

        TransactionSynchronizationUtils.triggerAfterCommit();
        verify(audit).info(anyString(), any(Object[].class));
    }

    @Test
    void record_writeFailsAfterCommit_doesNotThrow() {
        doThrow(new IllegalStateException("log sink down")).when(audit).info(anyString(), any(Object[].class));
        TransactionSynchronizationManager.initSynchronization();
        trail.record(SANDBOX, event());

        assertThatNoException().isThrownBy(TransactionSynchronizationUtils::triggerAfterCommit);
    }

    @Test
    void record_writeFailsOutsideTransaction_doesNotThrow() {
        doThrow(new IllegalStateException("log sink down")).when(audit).info(anyString(), any(Object[].class));

        assertThatNoException().isThrownBy(() -> trail.record(SANDBOX, event()));
    }

    private static JourneyAuditTrail.Event event() {
        return new JourneyAuditTrail.Event(UUID.randomUUID(), JourneyAuditTrail.Kind.STARTED, UUID.randomUUID(),
                JourneyState.IN_PROGRESS, JourneyStep.STARTED, Optional.empty(), Instant.now());
    }
}
