package es.in2.issuanceportal.authenticsource.infrastructure.web;

import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.session.api.EntitlementRecord;
import es.in2.issuanceportal.session.api.HolderIdentity;
import es.in2.issuanceportal.session.api.IdentifierType;
import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.session.api.PersonalIdentifier;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.shared.security.HolderRefs;
import es.in2.issuanceportal.shared.security.TestHolder;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /entitlement/requests/{id}/dispute} against the simulated source, configured as the platform loads
 * cgcom ({@code doctorid-portal-config.json}, a copy of {@code local-env/postgres/portal-config/cgcom.json}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class DisputeApiIT {

    private static final String CONTEXT = "/issuance-portal";
    private static final String SANDBOX_HOST = "sandbox.127.0.0.1.nip.io";
    private static final TenantId SANDBOX = new TenantId("sandbox");
    /** Whatever personal data the checks handle: none of it may reach a log line. */
    private static final List<String> PERSONAL_DATA = List.of("00000001R", "00000004G", "00000012N", "00000013J",
            "280000001", "280000004", "280000012", "280000013", "Lucía", "Prueba Activa", "SIM-0001-A", "SIM-0004-A",
            "SIM-0004-B", "SIM-0012-A", "SIM-0013-A", "Colegio de Médicos", "Secretaría del Colegio");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JourneySessions sessions;
    @Autowired
    private HolderRefs holderRefs;

    @BeforeEach
    void loadTheDoctorIdConfiguration() throws Exception {
        String config = new ClassPathResource("authenticsource/doctorid-portal-config.json")
                .getContentAsString(StandardCharsets.UTF_8);
        jdbc.sql("INSERT INTO \"sandbox_issuance_portal\".portal_config (tenant, config) VALUES ('sandbox', CAST(:config AS jsonb)) "
                        + "ON CONFLICT (tenant) DO UPDATE SET config = EXCLUDED.config")
                .param("config", config)
                .update();
    }

    @AfterEach
    void clearConfiguration() {
        jdbc.sql("DELETE FROM \"sandbox_issuance_portal\".portal_config").update();
    }

    @Test
    @DisplayName("AC-06 — dispute_availableRequest_is204AndTheRequestIsUnderReview")
    void dispute_availableRequest_is204AndTheRequestIsUnderReview(CapturedOutput output) throws Exception {
        int before = output.getOut().length();
        TestHolder holder = checkedJourney("00000001R");

        mvc.perform(dispute(holder, "r1")).andExpect(status().isNoContent());

        assertThat(section(holder).requests().getFirst().status()).isEqualTo(EntitlementRecord.Status.UNDER_REVIEW);
        String logged = output.getOut().substring(before);
        assertThat(disputeTraces(logged)).singleElement().satisfies(trace -> assertThat(trace)
                .contains("\"result\":\"sent\"", "\"adapter\":\"simulated\"", "\"attestation_type\":\"doctorid.sd.1\"",
                        "\"duration_ms\":"));
        assertThat(logged).doesNotContain(PERSONAL_DATA.toArray(String[]::new));
    }

    @Test
    @DisplayName("AC-06 — dispute_bodyIsIgnored")
    void dispute_bodyIsIgnored() throws Exception {
        TestHolder holder = checkedJourney("00000001R");

        mvc.perform(dispute(holder, "r1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"no\", \"personal_id\": \"00000009D\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("EC-01 — dispute_repeated_is204WithoutTellingTheSourceAgain")
    void dispute_repeated_is204WithoutTellingTheSourceAgain(CapturedOutput output) throws Exception {
        int before = output.getOut().length();
        TestHolder holder = checkedJourney("00000001R");
        mvc.perform(dispute(holder, "r1")).andExpect(status().isNoContent());

        mvc.perform(dispute(holder, "r1")).andExpect(status().isNoContent());

        assertThat(disputeTraces(output.getOut().substring(before))).hasSize(1);
        assertThat(section(holder).requests().getFirst().status()).isEqualTo(EntitlementRecord.Status.UNDER_REVIEW);
    }

    @Test
    @DisplayName("ES-02 — dispute_unknownId_is404RequestNotFound")
    void dispute_unknownId_is404RequestNotFound() throws Exception {
        TestHolder holder = checkedJourney("00000001R");

        mvc.perform(dispute(holder, "r9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("request_not_found"));
    }

    @Test
    @DisplayName("ES-02 — dispute_requestWithDataThatDoesNotMatch_is409RequestNotAvailable")
    void dispute_requestWithDataThatDoesNotMatch_is409RequestNotAvailable() throws Exception {
        TestHolder holder = checkedJourney("00000004G");

        mvc.perform(dispute(holder, "r2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("request_not_available"));

        mvc.perform(dispute(holder, "r1")).andExpect(status().isNoContent());
        assertThat(section(holder).requests()).extracting(EntitlementRecord.Request::status)
                .containsExactly(EntitlementRecord.Status.UNDER_REVIEW, EntitlementRecord.Status.IDENTITY_MISMATCH);
    }

    @Test
    @DisplayName("AC-06 — dispute_requestsNotCheckedYet_is409StepNotAllowed")
    void dispute_requestsNotCheckedYet_is409StepNotAllowed() throws Exception {
        TestHolder holder = journeyWith("00000001R");

        mvc.perform(dispute(holder, "r1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("step_not_allowed"));
    }

    @Test
    @DisplayName("ES-08 — dispute_journeyWithdrawnByTheCheck_is410JourneyClosed")
    void dispute_journeyWithdrawnByTheCheck_is410JourneyClosed() throws Exception {
        TestHolder holder = journeyWith("00000002W");
        mvc.perform(entitlement(holder)).andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("no_requests"));

        mvc.perform(dispute(holder, "r1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("journey_closed"));
    }

    @Test
    @DisplayName("ES-01 — dispute_idOfMoreThanSixtyFourCharacters_is400")
    void dispute_idOfMoreThanSixtyFourCharacters_is400() throws Exception {
        TestHolder holder = checkedJourney("00000001R");

        mvc.perform(dispute(holder, "r".repeat(65)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("bad_request"));
        mvc.perform(dispute(holder, "r".repeat(64))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("EC-09 — dispute_sourceThatDoesNotSupportIt_is204AndTracedNotSupported")
    void dispute_sourceThatDoesNotSupportIt_is204AndTracedNotSupported(CapturedOutput output) throws Exception {
        int before = output.getOut().length();
        TestHolder holder = checkedJourney("00000012N");

        mvc.perform(dispute(holder, "r1")).andExpect(status().isNoContent());

        assertThat(section(holder).requests().getFirst().status()).isEqualTo(EntitlementRecord.Status.UNDER_REVIEW);
        String logged = output.getOut().substring(before);
        assertThat(disputeTraces(logged)).singleElement().satisfies(trace ->
                assertThat(trace).contains("\"result\":\"not_supported\""));
        assertThat(logged).doesNotContain(PERSONAL_DATA.toArray(String[]::new));
    }

    @Test
    @DisplayName("AC-06 — dispute_sourceThatFails_is204AndTracedFailed")
    void dispute_sourceThatFails_is204AndTracedFailed(CapturedOutput output) throws Exception {
        int before = output.getOut().length();
        TestHolder holder = checkedJourney("00000013J");

        mvc.perform(dispute(holder, "r1")).andExpect(status().isNoContent());

        assertThat(section(holder).requests().getFirst().status()).isEqualTo(EntitlementRecord.Status.UNDER_REVIEW);
        String logged = output.getOut().substring(before);
        assertThat(disputeTraces(logged)).singleElement().satisfies(trace ->
                assertThat(trace).contains("\"result\":\"failed\""));
        assertThat(logged).contains("failed: IllegalStateException").doesNotContain(PERSONAL_DATA.toArray(String[]::new));
    }

    @Test
    @DisplayName("EC-02 — dispute_thenCheckAgain_requestStaysUnderReviewWithItsData")
    void dispute_thenCheckAgain_requestStaysUnderReviewWithItsData() throws Exception {
        TestHolder holder = checkedJourney("00000001R");
        mvc.perform(dispute(holder, "r1")).andExpect(status().isNoContent());

        // The only request is under review, so the check is not confirmed and the journey ends.
        mvc.perform(entitlement(holder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("not_confirmed"))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.requests[0].id").value("r1"))
                .andExpect(jsonPath("$.requests[0].status").value("under_review"))
                .andExpect(jsonPath("$.requests[0].attestation.type_name").value("DoctorID"));
    }

    @Test
    @DisplayName("EC-02 — dispute_oneOfTwoAvailable_staysUnderReviewAfterTheNextCheckAndTheOtherIsStillConfirmed")
    void dispute_oneOfTwoAvailable_staysUnderReviewAfterTheNextCheckAndTheOtherIsStillConfirmed() throws Exception {
        TestHolder holder = checkedJourney("00000005M");
        mvc.perform(dispute(holder, "r1")).andExpect(status().isNoContent());

        mvc.perform(entitlement(holder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("confirmed"))
                .andExpect(jsonPath("$.requests[0].status").value("under_review"))
                .andExpect(jsonPath("$.requests[1].status").value("identity_mismatch"))
                .andExpect(jsonPath("$.requests[2].status").value("available"));
    }

    private TestHolder checkedJourney(String dni) throws Exception {
        TestHolder holder = journeyWith(dni);
        mvc.perform(entitlement(holder)).andExpect(status().isOk()).andExpect(jsonPath("$.requests").isNotEmpty());
        return holder;
    }

    private TestHolder journeyWith(String dni) throws Exception {
        TestHolder holder = TestHolder.of(SANDBOX, holderRefs);
        mvc.perform(MockMvcRequestBuilders.post(CONTEXT + "/api/v1/journey")
                        .contextPath(CONTEXT)
                        .with(host())
                        .with(holder.token()))
                .andExpect(status().isCreated());
        sessions.write(SANDBOX, journeyOf(holder), Section.IDENTITY, new HolderIdentity("Lucía", "Prueba Activa",
                new PersonalIdentifier(IdentifierType.PERSONAL_ID, dni), null).toJson());
        return holder;
    }

    private UUID journeyOf(TestHolder holder) {
        return sessions.journeyOf(SANDBOX, holder.ref()).orElseThrow();
    }

    private EntitlementRecord section(TestHolder holder) {
        return EntitlementRecord.fromJson(sessions.read(SANDBOX, journeyOf(holder), Section.ENTITLEMENT).orElseThrow());
    }

    private static List<String> disputeTraces(String logged) {
        return logged.lines().filter(line -> line.contains("\"audit.authentic_source\"")
                && line.contains("entitlement dispute")).toList();
    }

    private static MockHttpServletRequestBuilder entitlement(TestHolder holder) {
        return MockMvcRequestBuilders.post(CONTEXT + "/api/v1/entitlement")
                .contextPath(CONTEXT)
                .with(holder.token())
                .with(host());
    }

    private static MockHttpServletRequestBuilder dispute(TestHolder holder, String id) {
        return MockMvcRequestBuilders.post(CONTEXT + "/api/v1/entitlement/requests/" + id + "/dispute")
                .contextPath(CONTEXT)
                .with(holder.token())
                .with(host());
    }

    private static RequestPostProcessor host() {
        return request -> {
            request.setServerName(SANDBOX_HOST);
            return request;
        };
    }
}
