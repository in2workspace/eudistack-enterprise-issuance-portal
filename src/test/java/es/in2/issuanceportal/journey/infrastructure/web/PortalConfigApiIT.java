package es.in2.issuanceportal.journey.infrastructure.web;

import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /config} end to end, against PostgreSQL: the configuration scenarios of ac.md. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PortalConfigApiIT {

    private static final String CONTEXT = "/issuance-portal";
    private static final String CONFIG = CONTEXT + "/api/v1/config";
    private static final TenantId SANDBOX = new TenantId("sandbox");
    private static final TenantId KPMG = new TenantId("kpmg");

    private static final String CONFIG_JSON = """
            {
              "texts": {"es": {"portal.app_name": "Portal de emisión"}},
              "legal_links": {"legal_notice": "https://example.org/aviso-legal", "privacy": "https://example.org/privacidad"},
              "support": {"email": "soporte@example.org", "phone": "900 000 000", "schedule": "L-V 9:00-18:00", "response_time": "48 horas"},
              "wallet_url": "https://%s.stg.eudistack.net/wallet",
              "attestation_type": "doctorid.sd.1",
              "data_placement": {"givenName": "identity.given_name"},
              "onboarding": {"enabled": true, "wallet_unit_service_url": "https://wallet-unit.internal"},
              "portal_client_id": "issuance-portal-%s",
              "identifier_types": {"doctorid.sd.1": "colegiado"}
            }""";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;

    /** The container is shared with the other integration tests, which expect no configuration. */
    @AfterEach
    void clearRows() {
        jdbc.sql("DELETE FROM \"sandbox_issuance_portal\".portal_config").update();
        jdbc.sql("DELETE FROM \"kpmg_issuance_portal\".portal_config").update();
    }

    @Test
    @DisplayName("AC-02 — getConfig_configuredTenant_returnsPublicPartOnlyWithoutSession")
    void getConfig_configuredTenant_returnsPublicPartOnlyWithoutSession() throws Exception {
        save(SANDBOX, CONFIG_JSON.formatted("sandbox", "sandbox"));

        mvc.perform(get("sandbox.127.0.0.1.nip.io"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(jsonPath("$", aMapWithSize(4)))
                .andExpect(jsonPath("$.texts.es['portal.app_name']").value("Portal de emisión"))
                .andExpect(jsonPath("$.legal_links.legal_notice").value("https://example.org/aviso-legal"))
                .andExpect(jsonPath("$.legal_links.privacy").value("https://example.org/privacidad"))
                .andExpect(jsonPath("$.legal_links.terms").doesNotExist())
                .andExpect(jsonPath("$.support.email").value("soporte@example.org"))
                .andExpect(jsonPath("$.support.phone").value("900 000 000"))
                .andExpect(jsonPath("$.support.schedule").value("L-V 9:00-18:00"))
                .andExpect(jsonPath("$.support.response_time").value("48 horas"))
                .andExpect(jsonPath("$.wallet_url").value("https://sandbox.stg.eudistack.net/wallet"));
    }

    @Test
    @DisplayName("AC-01 — getConfig_tenantWithoutConfiguration_isConfigUnavailable")
    void getConfig_tenantWithoutConfiguration_isConfigUnavailable() throws Exception {
        mvc.perform(get("kpmg.127.0.0.1.nip.io"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("config_unavailable"));
    }

    @Test
    @DisplayName("AC-03 — getConfig_walletUrlNotHttps_isConfigUnavailable")
    void getConfig_walletUrlNotHttps_isConfigUnavailable() throws Exception {
        save(KPMG, CONFIG_JSON.formatted("kpmg", "kpmg").replace("https://kpmg", "http://kpmg"));

        mvc.perform(get("kpmg.127.0.0.1.nip.io"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("config_unavailable"));
    }

    @Test
    @DisplayName("AC-09 — getConfig_twoTenants_eachGetsOnlyItsOwn")
    void getConfig_twoTenants_eachGetsOnlyItsOwn() throws Exception {
        save(SANDBOX, CONFIG_JSON.formatted("sandbox", "sandbox"));
        save(KPMG, CONFIG_JSON.formatted("kpmg", "kpmg"));

        mvc.perform(get("sandbox.127.0.0.1.nip.io"))
                .andExpect(jsonPath("$.wallet_url").value("https://sandbox.stg.eudistack.net/wallet"));
        mvc.perform(get("kpmg.127.0.0.1.nip.io"))
                .andExpect(jsonPath("$.wallet_url").value("https://kpmg.stg.eudistack.net/wallet"));
    }

    private static MockHttpServletRequestBuilder get(String host) {
        return MockMvcRequestBuilders.get(CONFIG)
                .contextPath(CONTEXT)
                .with(request -> {
                    request.setServerName(host);
                    return request;
                });
    }

    private void save(TenantId tenant, String config) {
        jdbc.sql("INSERT INTO " + tenant.schema() + ".portal_config (tenant, config) VALUES (:tenant, CAST(:config AS jsonb)) "
                        + "ON CONFLICT (tenant) DO UPDATE SET config = EXCLUDED.config, updated_at = now()")
                .param("tenant", tenant.value())
                .param("config", config)
                .update();
    }
}
