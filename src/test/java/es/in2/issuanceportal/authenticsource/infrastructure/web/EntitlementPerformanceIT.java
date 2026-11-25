package es.in2.issuanceportal.authenticsource.infrastructure.web;

import es.in2.issuanceportal.LatencySample;
import es.in2.issuanceportal.TestcontainersConfiguration;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * EUD-359 NFR-2: the entitlement check and the data dispute, with the simulated source answering at once, take less
 * than 300 ms at p95. Measured at the HTTP boundary of the service like the confirmation (ConfirmationPerformanceIT),
 * each call on a fresh journey, after a warm-up; the source's own time is out of the figure, as the criterion says.
 * Tagged so a slow runner can leave it out with {@code excludeTags 'performance'}.
 */
@Tag("performance")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EntitlementPerformanceIT {

    private static final String CONTEXT = "/issuance-portal";
    private static final String SANDBOX_HOST = "sandbox.127.0.0.1.nip.io";
    private static final TenantId SANDBOX = new TenantId("sandbox");
    /** A synthetic person of the fixture with one available request and no delay. */
    private static final String DNI = "00000001R";
    private static final int WARM_UP = 30;
    private static final int MEASURED = 200;
    private static final long P95_LIMIT_MILLIS = 300;

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
        jdbc.sql("INSERT INTO \"sandbox_issuance_portal\".portal_config (tenant, config) VALUES ('sandbox', "
                        + "CAST(:config AS jsonb)) ON CONFLICT (tenant) DO UPDATE SET config = EXCLUDED.config")
                .param("config", config)
                .update();
    }

    @AfterEach
    void clearConfiguration() {
        jdbc.sql("DELETE FROM \"sandbox_issuance_portal\".portal_config").update();
    }

    @Test
    @DisplayName("EUD-359 NFR-2 — 200 entitlement checks with the source answering at once: p95 under 300 ms")
    void check_sourceWithoutLatency_p95IsUnder300Millis() throws Exception {
        LatencySample sample = new LatencySample("entitlement check");
        for (int i = 0; i < WARM_UP + MEASURED; i++) {
            TestHolder holder = journey();
            if (i < WARM_UP) {
                check(holder);
            } else {
                sample.time(() -> check(holder));
            }
        }

        assertThat(sample.p95Millis()).as("p95 of the entitlement check in ms").isLessThan(P95_LIMIT_MILLIS);
    }

    @Test
    @DisplayName("EUD-359 NFR-2 — 200 data disputes with the source answering at once: p95 under 300 ms")
    void dispute_sourceWithoutLatency_p95IsUnder300Millis() throws Exception {
        LatencySample sample = new LatencySample("data dispute");
        for (int i = 0; i < WARM_UP + MEASURED; i++) {
            TestHolder holder = journey();
            check(holder);
            if (i < WARM_UP) {
                mvc.perform(request(holder, "/entitlement/requests/r1/dispute")).andExpect(status().isNoContent());
            } else {
                sample.time(() -> mvc.perform(request(holder, "/entitlement/requests/r1/dispute"))
                        .andExpect(status().isNoContent()));
            }
        }

        assertThat(sample.p95Millis()).as("p95 of the data dispute in ms").isLessThan(P95_LIMIT_MILLIS);
    }

    private void check(TestHolder holder) throws Exception {
        mvc.perform(request(holder, "/entitlement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("confirmed"));
    }

    private TestHolder journey() throws Exception {
        TestHolder holder = TestHolder.of(SANDBOX, holderRefs);
        mvc.perform(request(holder, "/journey")).andExpect(status().isCreated());
        sessions.write(SANDBOX, sessions.journeyOf(SANDBOX, holder.ref()).orElseThrow(), Section.IDENTITY,
                new HolderIdentity("Lucía", "Prueba Activa", new PersonalIdentifier(IdentifierType.PERSONAL_ID, DNI),
                        null).toJson());
        return holder;
    }

    private static MockHttpServletRequestBuilder request(TestHolder holder, String path) {
        return MockMvcRequestBuilders.post(CONTEXT + "/api/v1" + path)
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
