package es.in2.issuanceportal.authenticsource.infrastructure.audit;

import es.in2.issuanceportal.authenticsource.application.port.AuthenticSourceAuditTrail;
import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import es.in2.issuanceportal.authenticsource.domain.NotConfirmedReason;
import es.in2.issuanceportal.authenticsource.domain.RequestStatus;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LogAuthenticSourceAuditTrailTest {

    private static final TenantId CGCOM = new TenantId("cgcom");

    private final Logger audit = mock(Logger.class);
    private final LogAuthenticSourceAuditTrail trail = new LogAuthenticSourceAuditTrail(audit);

    @Test
    void append_check_writesEveryFieldOfTheTrace() {
        UUID journeyId = UUID.randomUUID();

        trail.append(CGCOM, new AuthenticSourceAuditTrail.Event(UUID.randomUUID(), journeyId, "doctorid.sd.1",
                "simulated", IdentifierType.COLEGIADO, AuthenticSourceAuditTrail.Result.NOT_CONFIRMED,
                Optional.empty(), Map.of(RequestStatus.IDENTITY_MISMATCH, 1, RequestStatus.AVAILABLE, 2),
                Duration.ofMillis(42), Instant.parse("2026-10-28T10:00:00Z")));

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(audit).info(anyString(), arguments.capture());
        assertThat(Arrays.stream(arguments.getValue()).map(String::valueOf).toList()).containsExactly(
                "journey_id=" + journeyId,
                arguments.getValue()[1].toString(),
                "tenant=cgcom",
                "attestation_type=doctorid.sd.1",
                "adapter=simulated",
                "identifier_type=colegiado",
                "result=not_confirmed",
                "reason=null",
                "requests_by_status={available=2, identity_mismatch=1}",
                "duration_ms=42",
                "occurred_at=2026-10-28T10:00:00Z");
    }

    @Test
    void append_checkWithoutRequests_writesTheReasonAndAnEmptyCount() {
        trail.append(CGCOM, new AuthenticSourceAuditTrail.Event(UUID.randomUUID(), UUID.randomUUID(),
                "doctorid.sd.1", "simulated", IdentifierType.PERSONAL_ID,
                AuthenticSourceAuditTrail.Result.NOT_CONFIRMED, Optional.of(NotConfirmedReason.NO_REQUESTS), Map.of(),
                Duration.ZERO, Instant.now()));

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(audit).info(anyString(), arguments.capture());
        assertThat(Arrays.stream(arguments.getValue()).map(String::valueOf).toList())
                .contains("reason=no_requests", "requests_by_status={}");
    }

    @Test
    void append_writeFails_doesNotThrow() {
        doThrow(new IllegalStateException("log sink down")).when(audit).info(anyString(), any(Object[].class));

        assertThatNoException().isThrownBy(() -> trail.append(CGCOM, new AuthenticSourceAuditTrail.Event(
                UUID.randomUUID(), UUID.randomUUID(), "doctorid.sd.1", "none", IdentifierType.PERSONAL_ID,
                AuthenticSourceAuditTrail.Result.CONFIRMED, Optional.empty(), Map.of(), Duration.ZERO, Instant.now())));
    }

    @Test
    void appendDispute_notice_writesEveryFieldOfTheTraceWithoutData() {
        UUID journeyId = UUID.randomUUID();

        trail.appendDispute(CGCOM, new AuthenticSourceAuditTrail.DisputeEvent(UUID.randomUUID(), journeyId,
                "doctorid.sd.1", "http", AuthenticSourceAuditTrail.DisputeResult.NOT_SUPPORTED, Duration.ofMillis(7),
                Instant.parse("2026-10-28T10:00:00Z")));

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(audit).info(anyString(), arguments.capture());
        assertThat(Arrays.stream(arguments.getValue()).map(String::valueOf).toList()).containsExactly(
                "journey_id=" + journeyId,
                arguments.getValue()[1].toString(),
                "tenant=cgcom",
                "attestation_type=doctorid.sd.1",
                "adapter=http",
                "result=not_supported",
                "duration_ms=7",
                "occurred_at=2026-10-28T10:00:00Z");
    }

    @Test
    void appendDispute_writeFails_doesNotThrow() {
        doThrow(new IllegalStateException("log sink down")).when(audit).info(anyString(), any(Object[].class));

        assertThatNoException().isThrownBy(() -> trail.appendDispute(CGCOM,
                new AuthenticSourceAuditTrail.DisputeEvent(UUID.randomUUID(), UUID.randomUUID(), "doctorid.sd.1",
                        "none", AuthenticSourceAuditTrail.DisputeResult.FAILED, Duration.ZERO, Instant.now())));
    }
}
