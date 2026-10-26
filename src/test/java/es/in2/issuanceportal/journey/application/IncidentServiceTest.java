package es.in2.issuanceportal.journey.application;

import es.in2.issuanceportal.journey.application.port.IncidentDeliveryException;
import es.in2.issuanceportal.journey.application.port.IncidentMessage;
import es.in2.issuanceportal.journey.domain.config.LegalLinks;
import es.in2.issuanceportal.journey.domain.config.PortalConfig;
import es.in2.issuanceportal.journey.domain.config.Support;
import es.in2.issuanceportal.journey.domain.incident.Incident;
import es.in2.issuanceportal.journey.domain.incident.IncidentType;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncidentServiceTest {

    private static final TenantId CGCOM = new TenantId("cgcom");
    private static final Incident INCIDENT = new Incident("Ana", "ana@example.org", null, IncidentType.ACCESS,
            "No puedo entrar", "Texto");

    private final List<IncidentMessage> sent = new ArrayList<>();

    @Test
    @DisplayName("AC-04 — send_configuredTenant_mailsSupportAndReturnsResponseTime")
    void send_configuredTenant_mailsSupportAndReturnsResponseTime() {
        IncidentService service = service(new Support("soporte@cgcom-identidad.es", null, null, "48 horas"));

        assertThat(service.send(CGCOM, INCIDENT)).isEqualTo("48 horas");
        assertThat(sent).singleElement().satisfies(message -> {
            assertThat(message.to()).isEqualTo("soporte@cgcom-identidad.es");
            assertThat(message.replyTo()).isEqualTo("ana@example.org");
        });
    }

    @Test
    @DisplayName("ES-04 — send_noSupportEmail_isUnavailableAndSendsNothing")
    void send_noSupportEmail_isUnavailableAndSendsNothing() {
        IncidentService service = service(new Support(null, "900", null, "48 horas"));

        assertUnavailable(() -> service.send(CGCOM, INCIDENT));
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("ES-04 — send_noResponseTime_isUnavailableAndSendsNothing")
    void send_noResponseTime_isUnavailableAndSendsNothing() {
        IncidentService service = service(new Support("soporte@cgcom-identidad.es", null, null, null));

        assertUnavailable(() -> service.send(CGCOM, INCIDENT));
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("ES-03 — send_deliveryFails_isUnavailable")
    void send_deliveryFails_isUnavailable() {
        PortalConfigService configs = new PortalConfigService(tenant -> Optional.of(config(
                new Support("soporte@cgcom-identidad.es", null, null, "48 horas"))));
        IncidentService service = new IncidentService(configs, message -> {
            throw new IncidentDeliveryException("SES MessageRejected");
        });

        assertUnavailable(() -> service.send(CGCOM, INCIDENT));
    }

    @Test
    @DisplayName("AC-01 — send_tenantWithoutConfiguration_isConfigUnavailable")
    void send_tenantWithoutConfiguration_isConfigUnavailable() {
        IncidentService service = new IncidentService(new PortalConfigService(tenant -> Optional.empty()), sent::add);

        assertThatThrownBy(() -> service.send(CGCOM, INCIDENT))
                .isInstanceOfSatisfying(ProblemException.class,
                        e -> assertThat(e.problem()).isEqualTo(ProblemCode.CONFIG_UNAVAILABLE));
        assertThat(sent).isEmpty();
    }

    private IncidentService service(Support support) {
        return new IncidentService(new PortalConfigService(tenant -> Optional.of(config(support))), sent::add);
    }

    private static PortalConfig config(Support support) {
        return new PortalConfig(Map.of(), new LegalLinks(null, null, null, null), support,
                "https://cgcom.stg.eudistack.net/wallet", null);
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ProblemException.class,
                e -> assertThat(e.problem()).isEqualTo(ProblemCode.SERVICE_UNAVAILABLE));
    }
}
