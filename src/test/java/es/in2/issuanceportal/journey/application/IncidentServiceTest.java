package es.in2.issuanceportal.journey.application;

import es.in2.issuanceportal.journey.application.port.IncidentAuditTrail;
import es.in2.issuanceportal.journey.application.port.IncidentAuditTrail.Outcome;
import es.in2.issuanceportal.journey.application.port.IncidentDeliveryException;
import es.in2.issuanceportal.journey.application.port.IncidentMailer;
import es.in2.issuanceportal.journey.application.port.IncidentMessage;
import es.in2.issuanceportal.journey.domain.config.LegalLinks;
import es.in2.issuanceportal.journey.domain.config.PortalConfig;
import es.in2.issuanceportal.journey.domain.config.Support;
import es.in2.issuanceportal.journey.domain.incident.Incident;
import es.in2.issuanceportal.journey.domain.incident.IncidentType;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncidentServiceTest {

    private static final TenantId CGCOM = new TenantId("cgcom");
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final Incident INCIDENT = new Incident("Ana", "ana@example.org", null, IncidentType.ACCESS,
            "No puedo entrar", "Texto");
    private static final Support SUPPORT = new Support("soporte@cgcom-identidad.es", null, null, "48 horas");

    private final List<IncidentMessage> sent = new ArrayList<>();
    private final List<IncidentAuditTrail.Event> events = new ArrayList<>();

    @Test
    @DisplayName("AC-04 — send_configuredTenant_mailsSupportAndReturnsResponseTime")
    void send_configuredTenant_mailsSupportAndReturnsResponseTime() {
        IncidentService service = service(SUPPORT, sent::add);

        assertThat(service.send(CGCOM, INCIDENT)).isEqualTo("48 horas");
        assertThat(sent).singleElement().satisfies(message -> {
            assertThat(message.to()).isEqualTo("soporte@cgcom-identidad.es");
            assertThat(message.replyTo()).isEqualTo("ana@example.org");
        });
    }

    @Test
    @DisplayName("AC-07 — send_delivered_tracesSentWithTypeOnly")
    void send_delivered_tracesSentWithTypeOnly() {
        service(SUPPORT, sent::add).send(CGCOM, INCIDENT);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo(Outcome.SENT);
            assertThat(event.type()).isEqualTo(IncidentType.ACCESS);
            assertThat(event.reason()).isEmpty();
            assertThat(event.occurredAt()).isEqualTo(NOW);
        });
    }

    @Test
    @DisplayName("ES-03 — send_deliveryFails_isUnavailableAndTracesFailedWithReason")
    void send_deliveryFails_isUnavailableAndTracesFailedWithReason() {
        IncidentService service = service(SUPPORT, message -> {
            throw new IncidentDeliveryException("SES MessageRejected");
        });

        assertUnavailable(() -> service.send(CGCOM, INCIDENT));
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo(Outcome.FAILED);
            assertThat(event.reason()).hasValueSatisfying(reason -> assertThat(reason).contains("MessageRejected"));
        });
    }

    @Test
    @DisplayName("ES-04 — send_noSupportEmail_isUnavailableAndSendsNothing")
    void send_noSupportEmail_isUnavailableAndSendsNothing() {
        IncidentService service = service(new Support(null, "900", null, "48 horas"), sent::add);

        assertUnavailable(() -> service.send(CGCOM, INCIDENT));
        assertThat(sent).isEmpty();
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("ES-04 — send_noResponseTime_isUnavailableAndSendsNothing")
    void send_noResponseTime_isUnavailableAndSendsNothing() {
        IncidentService service = service(new Support("soporte@cgcom-identidad.es", null, null, null), sent::add);

        assertUnavailable(() -> service.send(CGCOM, INCIDENT));
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("AC-01 — send_tenantWithoutConfiguration_isConfigUnavailable")
    void send_tenantWithoutConfiguration_isConfigUnavailable() {
        IncidentService service = new IncidentService(new PortalConfigService(tenant -> Optional.empty()), sent::add,
                (tenant, event) -> events.add(event), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.send(CGCOM, INCIDENT))
                .isInstanceOfSatisfying(ProblemException.class,
                        e -> assertThat(e.problem()).isEqualTo(ProblemCode.CONFIG_UNAVAILABLE));
        assertThat(sent).isEmpty();
        assertThat(events).isEmpty();
    }

    private IncidentService service(Support support, IncidentMailer mailer) {
        return new IncidentService(new PortalConfigService(tenant -> Optional.of(config(support))), mailer,
                (tenant, event) -> events.add(event), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PortalConfig config(Support support) {
        return new PortalConfig(Map.of(), new LegalLinks(null, null, null, null), support,
                "https://cgcom.stg.eudistack.net/wallet", null);
    }

    private static void assertUnavailable(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ProblemException.class,
                e -> assertThat(e.problem()).isEqualTo(ProblemCode.SERVICE_UNAVAILABLE));
    }
}
