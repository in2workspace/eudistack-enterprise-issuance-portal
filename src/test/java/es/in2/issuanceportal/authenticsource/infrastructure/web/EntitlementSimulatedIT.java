package es.in2.issuanceportal.authenticsource.infrastructure.web;

import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.session.api.HolderIdentity;
import es.in2.issuanceportal.session.api.IdentifierType;
import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.session.api.PersonalIdentifier;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.session.api.SessionId;
import es.in2.issuanceportal.shared.tenant.TenantId;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The criteria of ac.md against the simulated source, configured exactly as the platform loads cgcom
 * ({@code local-env/postgres/portal-config/cgcom.json} of eudistack-platform-dev, copied as the fixture).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class EntitlementSimulatedIT {

    private static final String CONTEXT = "/issuance-portal";
    private static final String SANDBOX_HOST = "sandbox.127.0.0.1.nip.io";
    private static final TenantId SANDBOX = new TenantId("sandbox");
    /** Whatever personal data the checks handle: none of it may reach a log line. */
    private static final List<String> PERSONAL_DATA = List.of("00000001R", "00000002W", "00000003A", "280000001",
            "280000002", "289999999", "00000009D", "Lucía", "Prueba Activa", "Prueba Baja", "Colegio de Médicos",
            "Cardiology", "Family and Community Medicine");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JourneySessions sessions;

    /**
     * The JSON is a copy of cgcom's local configuration in eudistack-platform-dev
     * ({@code local-env/postgres/portal-config/cgcom.json}), loaded for sandbox: change both together.
     */
    @BeforeEach
    void loadTheDoctorIdConfiguration() throws Exception {
        String config = new ClassPathResource("authenticsource/doctorid-portal-config.json")
                .getContentAsString(StandardCharsets.UTF_8);
        jdbc.sql("INSERT INTO \"sandbox_issuance_portal\".portal_config (tenant, config) VALUES ('sandbox', CAST(:config AS jsonb)) "
                        + "ON CONFLICT (tenant) DO UPDATE SET config = EXCLUDED.config")
                .param("config", config)
                .update();
    }

    /** The container is shared with the other integration tests, which expect no configuration. */
    @AfterEach
    void clearConfiguration() {
        jdbc.sql("DELETE FROM \"sandbox_issuance_portal\".portal_config").update();
    }

    @Test
    @DisplayName("AC-03, AC-04 — postEntitlement_activeByDni_confirmsAndKeepsTheDataEncrypted")
    void postEntitlement_activeByDni_confirmsAndKeepsTheDataEncrypted() throws Exception {
        SessionId session = journeyWith(IdentifierType.DNI, "00000001R");

        mvc.perform(entitlement(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("confirmed"))
                .andExpect(jsonPath("$.attestation.type_name").value("DoctorID"))
                .andExpect(jsonPath("$.attestation.fields[0].value").value("Lucía Prueba Activa"))
                .andExpect(jsonPath("$.attestation.fields[1].label").value("Número de colegiado"))
                .andExpect(jsonPath("$.attestation.fields[1].value").value("280000001"))
                .andExpect(jsonPath("$.attestation.fields[2].value").value("Colegio de Médicos de Prueba"))
                .andExpect(jsonPath("$.attestation.fields[3].value").value("Family and Community Medicine, Cardiology"))
                .andExpect(jsonPath("$.attestation.fields[4].value").value("Sí"))
                .andExpect(jsonPath("$.attestation.email_required").value(true))
                .andExpect(jsonPath("$.attestation.fields[?(@.value == '00000001R')]").isEmpty());

        UUID journeyId = sessions.journeyOf(SANDBOX, session).orElseThrow();
        assertThat(journeyRow(journeyId)).containsEntry("step", "entitlement_confirmed");
        assertThat(sessions.read(SANDBOX, session, Section.ENTITLEMENT)).isPresent();
        assertThat(jdbc.sql("SELECT position(convert_to('280000001', 'UTF8') in entitlement) "
                        + "FROM \"sandbox_issuance_portal\".journey_session WHERE journey_id = :id")
                .param("id", journeyId).query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("AC-05 — postEntitlement_activeByRegistrationNumber_confirms")
    void postEntitlement_activeByRegistrationNumber_confirms() throws Exception {
        SessionId session = journeyWith(IdentifierType.COLEGIADO, "280000001");

        mvc.perform(entitlement(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("confirmed"));
    }

    @ParameterizedTest
    @CsvSource({"DNI,00000002W,not_entitled", "DNI,00000009D,not_found", "COLEGIADO,289999999,not_found",
        "COLEGIADO,280000002,not_entitled"})
    @DisplayName("AC-07 — postEntitlement_deregisteredOrUnknown_isNotConfirmedAndTheJourneyWithdrawn")
    void postEntitlement_deregisteredOrUnknown_isNotConfirmedAndTheJourneyWithdrawn(IdentifierType type, String value,
                                                                                    String reason) throws Exception {
        SessionId session = journeyWith(type, value);
        UUID journeyId = sessions.journeyOf(SANDBOX, session).orElseThrow();

        mvc.perform(entitlement(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("not_confirmed"))
                .andExpect(jsonPath("$.reason").value(reason));

        assertThat(journeyRow(journeyId))
                .containsEntry("state", "withdrawn")
                .containsEntry("withdrawal_reason", "not_entitled");
        assertThat(jdbc.sql("SELECT count(*) FROM \"sandbox_issuance_portal\".journey_session WHERE journey_id = :id")
                .param("id", journeyId).query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("AC-09, NFR-2 — postEntitlement_slowRecord_isUnavailableWithinTheTimeout")
    void postEntitlement_slowRecord_isUnavailableWithinTheTimeout(CapturedOutput output) throws Exception {
        int before = output.getOut().length();
        SessionId session = journeyWith(IdentifierType.DNI, "00000003A");
        UUID journeyId = sessions.journeyOf(SANDBOX, session).orElseThrow();

        long start = System.nanoTime();
        mvc.perform(entitlement(session))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"))
                .andExpect(jsonPath("$.type").value("authentic_source_unavailable"));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(took).isBetween(Duration.ofSeconds(10), Duration.ofSeconds(11));
        assertThat(journeyRow(journeyId)).containsEntry("state", "in_progress").containsEntry("step", "started");
        assertThat(sessions.read(SANDBOX, session, Section.ENTITLEMENT)).isEmpty();
        String logged = output.getOut().substring(before);
        assertThat(logged).contains("\"result\":\"unavailable\"")
                .doesNotContain(PERSONAL_DATA.toArray(String[]::new))
                .doesNotContain("Prueba Lenta", "Geriatrics");
    }

    @Test
    @DisplayName("AC-11, NFR-5, NFR-6 — postEntitlement_everyCheck_tracedWithoutPersonalData")
    void postEntitlement_everyCheck_tracedWithoutPersonalData(CapturedOutput output) throws Exception {
        int before = output.getOut().length();

        mvc.perform(entitlement(journeyWith(IdentifierType.DNI, "00000001R"))).andExpect(status().isOk());
        mvc.perform(entitlement(journeyWith(IdentifierType.COLEGIADO, "280000002"))).andExpect(status().isOk());
        mvc.perform(entitlement(journeyWith(IdentifierType.DNI, "00000009D"))).andExpect(status().isOk());

        String logged = output.getOut().substring(before);
        List<String> traces = logged.lines().filter(line -> line.contains("\"audit.authentic_source\"")).toList();
        assertThat(traces).hasSize(3);
        assertThat(traces.get(0)).contains("\"result\":\"confirmed\"", "\"identifier_type\":\"dni\"",
                "\"adapter\":\"simulated\"", "\"attestation_type\":\"doctorid.sd.1\"", "\"duration_ms\":");
        assertThat(traces.get(1)).contains("\"result\":\"not_confirmed\"", "\"reason\":\"not_entitled\"",
                "\"identifier_type\":\"colegiado\"");
        assertThat(traces.get(2)).contains("\"reason\":\"not_found\"");
        assertThat(logged).doesNotContain(PERSONAL_DATA.toArray(String[]::new));
    }

    @Test
    @DisplayName("NFR-1 — postEntitlement_activeHolder_p95UnderThreeHundredMilliseconds")
    void postEntitlement_activeHolder_p95UnderThreeHundredMilliseconds() throws Exception {
        SessionId session = journeyWith(IdentifierType.DNI, "00000001R");
        for (int i = 0; i < 20; i++) {
            mvc.perform(entitlement(session)).andExpect(status().isOk());
        }

        List<Long> micros = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            long start = System.nanoTime();
            mvc.perform(entitlement(session)).andExpect(status().isOk());
            micros.add((System.nanoTime() - start) / 1_000);
        }

        micros.sort(Comparator.naturalOrder());
        long p95 = micros.get((int) Math.ceil(micros.size() * 0.95) - 1);
        System.out.printf("NFR-1 entitlement check p95=%.1f ms p50=%.1f ms max=%.1f ms over %d calls%n",
                p95 / 1000.0, micros.get(micros.size() / 2) / 1000.0, micros.getLast() / 1000.0, micros.size());
        assertThat(Duration.ofNanos(p95 * 1_000)).isLessThan(Duration.ofMillis(300));
    }

    /** A journey whose identity is what the Journey module writes from the holder's Access Token. */
    private SessionId journeyWith(IdentifierType type, String value) throws Exception {
        Cookie cookie = mvc.perform(MockMvcRequestBuilders.post(CONTEXT + "/api/v1/journey")
                        .contextPath(CONTEXT)
                        .header("X-Requested-With", "issuance-portal")
                        .with(host()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getCookie("journey_session");
        SessionId session = SessionId.parse(cookie.getValue()).orElseThrow();
        sessions.write(SANDBOX, session, Section.IDENTITY,
                new HolderIdentity("Lucía", "Prueba Activa", new PersonalIdentifier(type, value), null).toJson());
        return session;
    }

    private Map<String, Object> journeyRow(UUID id) {
        return jdbc.sql("SELECT state, step, withdrawal_reason FROM \"sandbox_issuance_portal\".journey WHERE id = :id")
                .param("id", id).query().singleRow();
    }

    private static MockHttpServletRequestBuilder entitlement(SessionId session) {
        return MockMvcRequestBuilders.post(CONTEXT + "/api/v1/entitlement")
                .contextPath(CONTEXT)
                .header("X-Requested-With", "issuance-portal")
                .cookie(new Cookie("journey_session", session.value()))
                .with(host());
    }

    private static RequestPostProcessor host() {
        return request -> {
            request.setServerName(SANDBOX_HOST);
            return request;
        };
    }
}
