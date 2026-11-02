package es.in2.issuanceportal.shared.web;

import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.journey.application.port.IncidentMailer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The forwarded headers behind one proxy, as in Docker Compose: the client's address is the one nginx
 * appended, whatever the client wrote before it, and the tenant still comes from {@code X-Forwarded-Host}.
 */
@SpringBootTest(properties = "issuance-portal.client-address.trusted-proxies=1")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ClientAddressIT {

    private static final String CONTEXT = "/issuance-portal";
    private static final String NGINX = "172.18.0.9";
    private static final String CLIENT = "198.51.100.20";
    private static final String SANDBOX_HOST = "sandbox.127.0.0.1.nip.io";

    private static final String VALID = """
            {"name": "Ana", "email": "ana@example.org", "type": "other", "subject": "S", "description": "D"}""";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;
    @MockitoBean
    private IncidentMailer mailer;

    @BeforeEach
    void configureSandbox() {
        jdbc.sql("INSERT INTO \"sandbox_issuance_portal\".portal_config (tenant, config) VALUES ('sandbox', "
                        + "'{\"texts\": {}, \"legal_links\": {}, \"support\": {\"email\": \"soporte@sandbox.example.org\", "
                        + "\"response_time\": \"48 horas\"}, \"wallet_url\": \"https://sandbox.stg.eudistack.net/wallet\"}'::jsonb)")
                .update();
    }

    /** The container is shared with the other integration tests, which expect no configuration. */
    @AfterEach
    void clearRows() {
        jdbc.sql("DELETE FROM \"sandbox_issuance_portal\".portal_config").update();
    }

    @Test
    @DisplayName("AC-06 — incidents_forgedForwardedFor_countsTheAddressNginxSaw")
    void incidents_forgedForwardedFor_countsTheAddressNginxSaw() throws Exception {
        for (int i = 1; i <= 5; i++) {
            mvc.perform(incident("203.0.113." + i + ", " + CLIENT)).andExpect(status().isAccepted());
        }

        mvc.perform(incident("203.0.113.6, " + CLIENT))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("too_many_requests"));
    }

    @Test
    void config_behindNginx_resolvesTheTenantFromForwardedHost() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get(CONTEXT + "/api/v1/config").contextPath(CONTEXT)
                        .header("X-Forwarded-Host", SANDBOX_HOST)
                        .header("X-Forwarded-For", CLIENT)
                        .with(request -> {
                            request.setServerName("issuance-portal-service");
                            request.setRemoteAddr(NGINX);
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.support.email").value("soporte@sandbox.example.org"));
    }

    private static MockHttpServletRequestBuilder incident(String forwardedFor) {
        return MockMvcRequestBuilders.post(CONTEXT + "/api/v1/incidents").contextPath(CONTEXT)
                .header("X-Requested-With", "issuance-portal")
                .header("X-Forwarded-For", forwardedFor)
                .header("X-Forwarded-Host", SANDBOX_HOST)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID)
                .with(request -> {
                    request.setServerName("issuance-portal-service");
                    request.setRemoteAddr(NGINX);
                    return request;
                });
    }
}
