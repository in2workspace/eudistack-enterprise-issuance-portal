package es.in2.issuanceportal.journey.infrastructure.audit;

import es.in2.issuanceportal.journey.application.port.IncidentAuditTrail;
import es.in2.issuanceportal.journey.application.port.IncidentAuditTrail.Outcome;
import es.in2.issuanceportal.journey.domain.incident.IncidentType;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LogIncidentAuditTrailTest {

    private static final TenantId CGCOM = new TenantId("cgcom");

    private final Logger audit = mock(Logger.class);
    private final LogIncidentAuditTrail trail = new LogIncidentAuditTrail(audit);

    @Test
    @DisplayName("AC-07 — append_failedEvent_writesTenantTypeOutcomeAndReason")
    void append_failedEvent_writesTenantTypeOutcomeAndReason() {
        trail.append(CGCOM, new IncidentAuditTrail.Event(UUID.randomUUID(), IncidentType.TECHNICAL, Outcome.FAILED,
                Optional.of("Incident not delivered: SES MessageRejected"), Instant.parse("2026-10-26T10:00:00Z")));

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(audit).info(anyString(), arguments.capture());
        String line = Arrays.stream(arguments.getValue()).map(String::valueOf).collect(Collectors.joining(" "));
        assertThat(line).contains("event=INCIDENT_FAILED", "tenant=cgcom", "incident_type=technical",
                "reason=Incident not delivered: SES MessageRejected", "occurred_at=2026-10-26T10:00:00Z");
    }

    @Test
    void append_writeFails_doesNotThrow() {
        doThrow(new IllegalStateException("log sink down")).when(audit).info(anyString(), any(Object[].class));

        assertThatNoException().isThrownBy(() -> trail.append(CGCOM, new IncidentAuditTrail.Event(
                UUID.randomUUID(), IncidentType.OTHER, Outcome.SENT, Optional.empty(), Instant.now())));
    }
}
