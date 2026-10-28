package es.in2.issuanceportal.authenticsource.infrastructure.audit;

import es.in2.issuanceportal.authenticsource.application.port.AuthenticSourceAuditTrail;
import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import es.in2.issuanceportal.authenticsource.domain.NotConfirmedReason;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
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
                Optional.of(NotConfirmedReason.IDENTIFIER_MISMATCH), Duration.ofMillis(42),
                Instant.parse("2026-10-28T10:00:00Z")));

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
                "reason=identifier_mismatch",
                "duration_ms=42",
                "occurred_at=2026-10-28T10:00:00Z");
    }

    @Test
    void append_writeFails_doesNotThrow() {
        doThrow(new IllegalStateException("log sink down")).when(audit).info(anyString(), any(Object[].class));

        assertThatNoException().isThrownBy(() -> trail.append(CGCOM, new AuthenticSourceAuditTrail.Event(
                UUID.randomUUID(), UUID.randomUUID(), "doctorid.sd.1", "none", IdentifierType.DNI,
                AuthenticSourceAuditTrail.Result.CONFIRMED, Optional.empty(), Duration.ZERO, Instant.now())));
    }
}
