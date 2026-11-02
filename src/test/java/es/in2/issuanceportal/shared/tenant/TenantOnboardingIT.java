package es.in2.issuanceportal.shared.tenant;

import es.in2.issuanceportal.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * EUD-276 AC-08: a tenant onboarded while the service runs is in the registry but has no schema. Its first
 * request creates the schema, so loading its configuration is enough, without a restart or a deployment.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TenantOnboardingIT {

    private static final String CONTEXT = "/issuance-portal";
    private static final String TENANT = "onboarded";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;

    /** The container is shared: the other tests expect only the tenants of the fixture. */
    @AfterEach
    void removeTenant() {
        jdbc.sql("DROP SCHEMA IF EXISTS \"" + TENANT + "_issuance_portal\" CASCADE").update();
        jdbc.sql("DELETE FROM public.tenant_registry WHERE schema_name = :tenant").param("tenant", TENANT).update();
    }

    @Test
    @DisplayName("AC-08 — config_tenantOnboardedWhileRunning_getsItsSchemaAndThenItsConfiguration")
    void config_tenantOnboardedWhileRunning_getsItsSchemaAndThenItsConfiguration() throws Exception {
        jdbc.sql("INSERT INTO public.tenant_registry (schema_name, display_name, status) VALUES (:tenant, 'Onboarded', 'active')")
                .param("tenant", TENANT).update();
        assertThat(schemaExists()).isFalse();

        mvc.perform(config())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("config_unavailable"));
        assertThat(schemaExists()).isTrue();

        jdbc.sql("INSERT INTO \"" + TENANT + "_issuance_portal\".portal_config (tenant, config) VALUES (:tenant, CAST(:config AS jsonb))")
                .param("tenant", TENANT)
                .param("config", """
                        {"texts": {}, "legal_links": {}, "support": {"email": "soporte@onboarded.example.org"},
                         "wallet_url": "https://onboarded.stg.eudistack.net/wallet"}""")
                .update();

        mvc.perform(config())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.support.email").value("soporte@onboarded.example.org"));
    }

    private boolean schemaExists() {
        return jdbc.sql("SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = :schema AND table_name = 'portal_config'")
                .param("schema", TENANT + "_issuance_portal")
                .query(Long.class).single() == 1;
    }

    private static MockHttpServletRequestBuilder config() {
        return MockMvcRequestBuilders.get(CONTEXT + "/api/v1/config").contextPath(CONTEXT)
                .with(request -> {
                    request.setServerName(TENANT + ".127.0.0.1.nip.io");
                    return request;
                });
    }
}
