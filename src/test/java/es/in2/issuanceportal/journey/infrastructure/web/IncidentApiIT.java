package es.in2.issuanceportal.journey.infrastructure.web;

import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.journey.application.port.IncidentDeliveryException;
import es.in2.issuanceportal.journey.application.port.IncidentMailer;
import es.in2.issuanceportal.journey.application.port.IncidentMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /incidents} end to end, against PostgreSQL: the incident scenarios of ac.md. The mailer is
 * replaced so the message that would be sent can be checked, and made to fail.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class IncidentApiIT {

    private static final String CONTEXT = "/issuance-portal";
    private static final String INCIDENTS = CONTEXT + "/api/v1/incidents";
    private static final String SANDBOX_HOST = "sandbox.127.0.0.1.nip.io";
    private static final String KPMG_HOST = "kpmg.127.0.0.1.nip.io";
    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger();

    private static final String CONFIG_JSON = """
            {
              "texts": {},
              "legal_links": {},
              "support": {"email": "%s", "response_time": "%s"},
              "wallet_url": "https://sandbox.stg.eudistack.net/wallet"
            }""";

    private static final String VALID = """
            {"name": "Ana García", "email": "ana@example.org", "phone": "600 111 222", "type": "access",
             "subject": "No puedo entrar", "description": "Tras identificarme vuelvo al inicio."}""";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;
    @MockitoBean
    private IncidentMailer mailer;

    /** The container is shared with the other integration tests, which expect no configuration. */
    @AfterEach
    void clearRows() {
        jdbc.sql("DELETE FROM \"sandbox_issuance_portal\".portal_config").update();
        jdbc.sql("DELETE FROM \"kpmg_issuance_portal\".portal_config").update();
    }

    @Test
    @DisplayName("AC-04 — postIncident_validIncident_mailsSupportWithHolderAsReplyToAndReturnsResponseTime")
    void postIncident_validIncident_mailsSupportWithHolderAsReplyToAndReturnsResponseTime() throws Exception {
        configure("sandbox", "soporte@sandbox.example.org", "48 horas");

        mvc.perform(post(VALID))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.response_time").value("48 horas"));

        IncidentMessage message = sentMessage();
        assertThat(message.to()).isEqualTo("soporte@sandbox.example.org");
        assertThat(message.replyTo()).isEqualTo("ana@example.org");
        assertThat(message.subject()).isEqualTo("[Incidencia] Problema de acceso: No puedo entrar");
        assertThat(message.body()).contains("Ana García", "600 111 222", "Tras identificarme vuelvo al inicio.");
    }

    @Test
    @DisplayName("AC-09 — postIncident_twoTenants_eachReachesItsOwnSupport")
    void postIncident_twoTenants_eachReachesItsOwnSupport() throws Exception {
        configure("sandbox", "soporte@sandbox.example.org", "48 horas");
        configure("kpmg", "soporte@kpmg.example.org", "24 horas");

        mvc.perform(post(VALID, SANDBOX_HOST)).andExpect(jsonPath("$.response_time").value("48 horas"));
        mvc.perform(post(VALID, KPMG_HOST)).andExpect(jsonPath("$.response_time").value("24 horas"));

        ArgumentCaptor<IncidentMessage> sent = ArgumentCaptor.forClass(IncidentMessage.class);
        verify(mailer, times(2)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(IncidentMessage::to)
                .containsExactly("soporte@sandbox.example.org", "soporte@kpmg.example.org");
    }

    @Test
    @DisplayName("AC-07, NFR-2 — postIncident_sent_tracesWithoutContentAndStoresNothing")
    void postIncident_sent_tracesWithoutContentAndStoresNothing(CapturedOutput output) throws Exception {
        configure("sandbox", "soporte@sandbox.example.org", "48 horas");

        mvc.perform(post(VALID)).andExpect(status().isAccepted());

        assertThat(output.getAll()).contains("audit.incident", "INCIDENT_SENT", "incident_type", "access")
                .doesNotContain("Ana García", "ana@example.org", "600 111 222", "No puedo entrar",
                        "Tras identificarme vuelvo al inicio.");
        assertThat(jdbc.sql("SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'sandbox_issuance_portal' AND table_name <> 'flyway_schema_history'")
                .query(String.class).list())
                .containsExactlyInAnyOrder("journey", "journey_session", "shedlock", "portal_config");
    }

    @Test
    @DisplayName("ES-03 — postIncident_deliveryFails_isUnavailableAndTracedAsFailedWithoutContent")
    void postIncident_deliveryFails_isUnavailableAndTracedAsFailedWithoutContent(CapturedOutput output) throws Exception {
        configure("sandbox", "soporte@sandbox.example.org", "48 horas");
        doThrow(new IncidentDeliveryException("SES MessageRejected")).when(mailer).send(any());

        mvc.perform(post(VALID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("service_unavailable"));

        assertThat(output.getAll()).contains("INCIDENT_FAILED", "MessageRejected")
                .doesNotContain("Ana García", "ana@example.org", "No puedo entrar");
    }

    @Test
    @DisplayName("ES-04 — postIncident_tenantWithoutSupportEmail_isUnavailableAndSendsNothing")
    void postIncident_tenantWithoutSupportEmail_isUnavailableAndSendsNothing() throws Exception {
        jdbc.sql("INSERT INTO \"sandbox_issuance_portal\".portal_config (tenant, config) VALUES ('sandbox', CAST(:config AS jsonb))")
                .param("config", """
                        {"texts": {}, "legal_links": {}, "support": {"phone": "900 000 000"},
                         "wallet_url": "https://sandbox.stg.eudistack.net/wallet"}""")
                .update();

        mvc.perform(post(VALID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("service_unavailable"));
        verify(mailer, never()).send(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"email\": \"ana@example.org\", \"type\": \"access\", \"subject\": \"S\", \"description\": \"D\"}",
            "{\"name\": \"Ana\", \"email\": \"ana-at-example\", \"type\": \"access\", \"subject\": \"S\", \"description\": \"D\"}",
            "{\"name\": \"Ana\", \"email\": \"ana@example.org\", \"type\": \"billing\", \"subject\": \"S\", \"description\": \"D\"}",
            "{\"name\": \"Ana\", \"email\": \"ana@example.org\", \"type\": \"access\", \"subject\": \" \", \"description\": \"D\"}",
            "{\"name\": \"Ana\", \"email\": \"ana@example.org\", \"phone\": \"0123456789012345678901234567890\", \"type\": \"access\", \"subject\": \"S\", \"description\": \"D\"}",
            "not json"})
    @DisplayName("AC-05, ES-02 — postIncident_missingOrMalformedField_isBadRequestAndSendsNothing")
    void postIncident_missingOrMalformedField_isBadRequestAndSendsNothing(String body) throws Exception {
        configure("sandbox", "soporte@sandbox.example.org", "48 horas");

        mvc.perform(post(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("bad_request"));
        verify(mailer, never()).send(any());
    }

    @Test
    @DisplayName("ES-02 — postIncident_descriptionLongerThanContract_isBadRequest")
    void postIncident_descriptionLongerThanContract_isBadRequest() throws Exception {
        configure("sandbox", "soporte@sandbox.example.org", "48 horas");
        String body = VALID.replace("Tras identificarme vuelvo al inicio.", "x".repeat(5001));

        mvc.perform(post(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid fields: description"));
    }

    @Test
    @DisplayName("ES-06 — postIncident_withoutRequestedWithHeader_isNotAllowed")
    void postIncident_withoutRequestedWithHeader_isNotAllowed() throws Exception {
        configure("sandbox", "soporte@sandbox.example.org", "48 horas");

        mvc.perform(postWithoutRequestedWith(VALID, SANDBOX_HOST))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("request_not_allowed"));
        verify(mailer, never()).send(any());
    }

    @Test
    @DisplayName("AC-06, EC-04 — postIncident_sixthWithinAnHourFromSameAddress_isTooManyRequests")
    void postIncident_sixthWithinAnHourFromSameAddress_isTooManyRequests() throws Exception {
        configure("sandbox", "soporte@sandbox.example.org", "48 horas");
        String address = "203.0.113.66";
        mvc.perform(from(post("{}"), address)).andExpect(status().isBadRequest());
        for (int i = 0; i < 5; i++) {
            mvc.perform(from(post(VALID), address)).andExpect(status().isAccepted());
        }

        mvc.perform(from(post(VALID), address))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("too_many_requests"));
        verify(mailer, times(5)).send(any());
        mvc.perform(post(VALID)).andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("AC-01 — postIncident_tenantWithoutConfiguration_isConfigUnavailable")
    void postIncident_tenantWithoutConfiguration_isConfigUnavailable() throws Exception {
        mvc.perform(post(VALID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("config_unavailable"));
        verify(mailer, never()).send(any());
    }

    private IncidentMessage sentMessage() {
        ArgumentCaptor<IncidentMessage> sent = ArgumentCaptor.forClass(IncidentMessage.class);
        verify(mailer).send(sent.capture());
        return sent.getValue();
    }

    private void configure(String tenant, String supportEmail, String responseTime) {
        jdbc.sql("INSERT INTO \"" + tenant + "_issuance_portal\".portal_config (tenant, config) "
                        + "VALUES (:tenant, CAST(:config AS jsonb)) ON CONFLICT (tenant) DO UPDATE SET config = EXCLUDED.config")
                .param("tenant", tenant)
                .param("config", CONFIG_JSON.formatted(supportEmail, responseTime))
                .update();
    }

    /** As the interface sends it, with the state-changing header. */
    private static MockHttpServletRequestBuilder post(String body) {
        return post(body, SANDBOX_HOST);
    }

    private static MockHttpServletRequestBuilder post(String body, String host) {
        return postWithoutRequestedWith(body, host).header("X-Requested-With", "issuance-portal");
    }

    /**
     * Each request comes from its own address unless a test sets one: the rate limiter is shared by every
     * test in the context.
     */
    private static MockHttpServletRequestBuilder postWithoutRequestedWith(String body, String host) {
        String address = "10.0.0." + NEXT_ADDRESS.incrementAndGet() % 250;
        return MockMvcRequestBuilders.post(INCIDENTS)
                .contextPath(CONTEXT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(request -> {
                    request.setServerName(host);
                    request.setRemoteAddr(address);
                    return request;
                });
    }

    private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder request, String address) {
        return request.with(r -> {
            r.setRemoteAddr(address);
            return r;
        });
    }
}
