package es.in2.issuanceportal.authenticsource.infrastructure.web;

import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.authenticsource.StubAdapter;
import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import es.in2.issuanceportal.authenticsource.domain.PersonalIdentifier;
import es.in2.issuanceportal.authenticsource.domain.RequestStatus;
import es.in2.issuanceportal.authenticsource.domain.SourceRequest;
import es.in2.issuanceportal.authenticsource.domain.SourceRequests;
import es.in2.issuanceportal.journey.api.JourneyWithdrawal;
import es.in2.issuanceportal.session.api.HolderIdentity;
import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.shared.security.HolderRefs;
import es.in2.issuanceportal.shared.security.TestHolder;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code POST /entitlement} end to end against PostgreSQL, with a source the test controls. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, EntitlementApiIT.StubSource.class})
class EntitlementApiIT {

    private static final String CONTEXT = "/issuance-portal";
    private static final String SANDBOX_HOST = "sandbox.127.0.0.1.nip.io";
    private static final TenantId SANDBOX = new TenantId("sandbox");
    private static final PersonalIdentifier ACTIVE = new PersonalIdentifier(IdentifierType.PERSONAL_ID, "00000001R");

    @TestConfiguration
    static class StubSource {
        @Bean
        StubAdapter stubAdapter() {
            return StubAdapter.searchingByBoth("stub");
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JourneySessions sessions;
    @Autowired
    private HolderRefs holderRefs;
    @Autowired
    private JourneyWithdrawal withdrawal;
    @Autowired
    private StubAdapter adapter;

    @BeforeEach
    void configureSource() {
        jdbc.sql("INSERT INTO \"sandbox_issuance_portal\".portal_config (tenant, config) VALUES ('sandbox', CAST(:config AS jsonb)) "
                        + "ON CONFLICT (tenant) DO UPDATE SET config = EXCLUDED.config")
                .param("config", """
                        {"attestation_type": "doctorid.sd.1",
                         "authentic_source": {"doctorid.sd.1": {
                           "type_name": "DoctorID", "adapter": "stub", "identifier_types": ["personal_id", "colegiado"],
                           "mapping": [{"attribute": "registration_number", "claim": "personal_administrative_number",
                                        "label": "Número de colegiado"}]}}}""")
                .update();
        adapter.answering(searched -> searched.identifier().sameAs(ACTIVE)
                ? SourceRequests.of(new SourceRequest("SIM-1", RequestStatus.AVAILABLE, List.of(), false, null, null,
                        null, null, Map.of("registration_number", "280000001")))
                : SourceRequests.notFound());
    }

    /** The container is shared with the other integration tests, which expect no configuration. */
    @AfterEach
    void clearConfiguration() {
        jdbc.sql("DELETE FROM \"sandbox_issuance_portal\".portal_config").update();
    }

    @Test
    @DisplayName("AC-06 — postEntitlement_bodyWithAnotherIdentifier_usesTheJourneysIdentity")
    void postEntitlement_bodyWithAnotherIdentifier_usesTheJourneysIdentity() throws Exception {
        TestHolder holder = journeyWithIdentity("00000001R");

        mvc.perform(entitlement(holder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personal_identifier\":\"00000002W\",\"personal_id\":\"00000002W\","
                                + "\"personal_administrative_number\":\"00000002W\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("confirmed"))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.attestation.type_name").value("DoctorID"))
                .andExpect(jsonPath("$.attestation.fields[0].label").value("Nombre y apellidos"))
                .andExpect(jsonPath("$.attestation.fields[0].value").value("Lucía Prueba"))
                .andExpect(jsonPath("$.attestation.fields[1].value").value("280000001"))
                .andExpect(jsonPath("$.attestation.email").doesNotExist())
                .andExpect(jsonPath("$.attestation.email_required").value(true));

        assertThat(adapter.searches()).last().isEqualTo(ACTIVE);
    }

    @Test
    @DisplayName("AC-07 — postEntitlement_unknownHolder_isNotConfirmedWithItsReason")
    void postEntitlement_unknownHolder_isNotConfirmedWithItsReason() throws Exception {
        TestHolder holder = journeyWithIdentity("00000009Y");

        mvc.perform(entitlement(holder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("not_confirmed"))
                .andExpect(jsonPath("$.reason").value("not_found"))
                .andExpect(jsonPath("$.attestation").doesNotExist());

        mvc.perform(entitlement(holder))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("journey_closed"));
    }

    @Test
    @DisplayName("AC-01 — postEntitlement_journeyWithoutIdentity_isStepNotAllowed")
    void postEntitlement_journeyWithoutIdentity_isStepNotAllowed() throws Exception {
        TestHolder holder = start();
        sessions.clear(SANDBOX, journeyOf(holder), Section.IDENTITY);
        int searches = adapter.searches().size();

        mvc.perform(entitlement(holder))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("step_not_allowed"));

        assertThat(adapter.searches()).hasSize(searches);
    }

    @Test
    @DisplayName("ES-05 (EUD-314) — postEntitlement_holderWithoutJourney_isJourneyClosed")
    void postEntitlement_holderWithoutJourney_isJourneyClosed() throws Exception {
        mvc.perform(post().with(TestHolder.of(SANDBOX, holderRefs).token()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("journey_closed"));
    }

    @Test
    @DisplayName("EC-04 — postEntitlement_withdrawnJourney_isJourneyClosed")
    void postEntitlement_withdrawnJourney_isJourneyClosed() throws Exception {
        TestHolder holder = journeyWithIdentity("00000001R");
        withdrawal.withdraw(SANDBOX, holder.ref(), JourneyWithdrawal.Cause.NOT_ENTITLED);

        mvc.perform(entitlement(holder))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("journey_closed"));
    }

    private TestHolder start() throws Exception {
        TestHolder holder = TestHolder.of(SANDBOX, holderRefs);
        mvc.perform(MockMvcRequestBuilders.post(CONTEXT + "/api/v1/journey")
                        .contextPath(CONTEXT)
                        .with(host())
                        .with(holder.token()))
                .andExpect(status().isCreated());
        return holder;
    }

    private UUID journeyOf(TestHolder holder) {
        return sessions.journeyOf(SANDBOX, holder.ref()).orElseThrow();
    }

    /** What the journey writes when it opens with the holder's Access Token. */
    private TestHolder journeyWithIdentity(String dni) throws Exception {
        TestHolder holder = start();
        sessions.write(SANDBOX, journeyOf(holder), Section.IDENTITY, new HolderIdentity("Lucía", "Prueba",
                new es.in2.issuanceportal.session.api.PersonalIdentifier(
                        es.in2.issuanceportal.session.api.IdentifierType.PERSONAL_ID, dni), null).toJson());
        return holder;
    }

    private static MockHttpServletRequestBuilder entitlement(TestHolder holder) {
        return post().with(holder.token());
    }

    private static MockHttpServletRequestBuilder post() {
        return MockMvcRequestBuilders.post(CONTEXT + "/api/v1/entitlement")
                .contextPath(CONTEXT)
                .with(host());
    }

    private static RequestPostProcessor host() {
        return request -> {
            request.setServerName(SANDBOX_HOST);
            return request;
        };
    }
}
