package es.in2.issuanceportal.journey.infrastructure.web;

import es.in2.issuanceportal.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code POST /incidents} end to end, against PostgreSQL, with the local mail provider. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class IncidentApiIT {

    private static final String CONTEXT = "/issuance-portal";
    private static final String INCIDENTS = CONTEXT + "/api/v1/incidents";
    private static final String SANDBOX_HOST = "sandbox.127.0.0.1.nip.io";
    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger();

    private static final String CONFIG_JSON = """
            {
              "texts": {},
              "legal_links": {},
              "support": {"email": "soporte@sandbox.example.org", "response_time": "48 horas"},
              "wallet_url": "https://sandbox.stg.eudistack.net/wallet"
            }""";

    private static final String VALID = """
            {"name": "Ana García", "email": "ana@example.org", "type": "access",
             "subject": "No puedo entrar", "description": "Tras identificarme vuelvo al inicio."}""";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;

    /** The container is shared with the other integration tests, which expect no configuration. */
    @AfterEach
    void clearRows() {
        jdbc.sql("DELETE FROM \"sandbox_issuance_portal\".portal_config").update();
    }

    @Test
    @DisplayName("AC-04 — postIncident_validIncident_isAcceptedWithResponseTime")
    void postIncident_validIncident_isAcceptedWithResponseTime() throws Exception {
        configureSandbox();

        mvc.perform(post(VALID))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.response_time").value("48 horas"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"email\": \"ana@example.org\", \"type\": \"access\", \"subject\": \"S\", \"description\": \"D\"}",
            "{\"name\": \"Ana\", \"email\": \"ana-at-example\", \"type\": \"access\", \"subject\": \"S\", \"description\": \"D\"}",
            "{\"name\": \"Ana\", \"email\": \"ana@example.org\", \"type\": \"billing\", \"subject\": \"S\", \"description\": \"D\"}",
            "{\"name\": \"Ana\", \"email\": \"ana@example.org\", \"type\": \"access\", \"subject\": \" \", \"description\": \"D\"}",
            "{\"name\": \"Ana\", \"email\": \"ana@example.org\", \"phone\": \"0123456789012345678901234567890\", \"type\": \"access\", \"subject\": \"S\", \"description\": \"D\"}",
            "not json"})
    @DisplayName("AC-05, ES-02 — postIncident_missingOrMalformedField_isBadRequest")
    void postIncident_missingOrMalformedField_isBadRequest(String body) throws Exception {
        configureSandbox();

        mvc.perform(post(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("bad_request"));
    }

    @Test
    @DisplayName("ES-02 — postIncident_descriptionLongerThanContract_isBadRequest")
    void postIncident_descriptionLongerThanContract_isBadRequest() throws Exception {
        configureSandbox();
        String body = VALID.replace("Tras identificarme vuelvo al inicio.", "x".repeat(5001));

        mvc.perform(post(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid fields: description"));
    }

    @Test
    @DisplayName("ES-06 — postIncident_withoutRequestedWithHeader_isNotAllowed")
    void postIncident_withoutRequestedWithHeader_isNotAllowed() throws Exception {
        configureSandbox();

        mvc.perform(postWithoutRequestedWith(VALID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("request_not_allowed"));
    }

    @Test
    @DisplayName("AC-06, EC-04 — postIncident_sixthWithinAnHourFromSameAddress_isTooManyRequests")
    void postIncident_sixthWithinAnHourFromSameAddress_isTooManyRequests() throws Exception {
        configureSandbox();
        String address = "203.0.113.66";
        mvc.perform(from(post("{}"), address)).andExpect(status().isBadRequest());
        for (int i = 0; i < 5; i++) {
            mvc.perform(from(post(VALID), address)).andExpect(status().isAccepted());
        }

        mvc.perform(from(post(VALID), address))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("too_many_requests"));
        mvc.perform(post(VALID)).andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("AC-01 — postIncident_tenantWithoutConfiguration_isConfigUnavailable")
    void postIncident_tenantWithoutConfiguration_isConfigUnavailable() throws Exception {
        mvc.perform(post(VALID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("config_unavailable"));
    }

    private void configureSandbox() {
        jdbc.sql("INSERT INTO \"sandbox_issuance_portal\".portal_config (tenant, config) VALUES ('sandbox', CAST(:config AS jsonb)) "
                        + "ON CONFLICT (tenant) DO UPDATE SET config = EXCLUDED.config")
                .param("config", CONFIG_JSON)
                .update();
    }

    /** As the interface sends it, with the state-changing header. */
    private static MockHttpServletRequestBuilder post(String body) {
        return postWithoutRequestedWith(body).header("X-Requested-With", "issuance-portal");
    }

    /**
     * Each request comes from its own address unless a test sets one: the rate limiter is shared by every
     * test in the context.
     */
    private static MockHttpServletRequestBuilder postWithoutRequestedWith(String body) {
        String address = "10.0.0." + NEXT_ADDRESS.incrementAndGet() % 250;
        return MockMvcRequestBuilders.post(INCIDENTS)
                .contextPath(CONTEXT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(request -> {
                    request.setServerName(SANDBOX_HOST);
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
