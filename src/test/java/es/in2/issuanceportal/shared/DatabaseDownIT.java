package es.in2.issuanceportal.shared;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ES-04: with PostgreSQL down every API path answers 503 {@code service_unavailable}. Runs on its own
 * container, which the test stops, so it never touches the database of the other tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
class DatabaseDownIT {

    private static final String CONTEXT = "/issuance-portal";
    private static final String JOURNEY = CONTEXT + "/api/v1/journey";

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withInitScript("db/tenant-registry.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.connection-timeout", () -> "1000");
    }

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("ES-04 — api_databaseDown_returns503ServiceUnavailable")
    void api_databaseDown_returns503ServiceUnavailable() throws Exception {
        // sandbox is now in the tenant cache, kpmg is not.
        MvcResult started = mvc.perform(post("sandbox")).andExpect(status().isCreated()).andReturn();
        Cookie session = started.getResponse().getCookie("journey_session");

        POSTGRES.stop();

        // Transaction path: the tenant is cached, the service's transaction cannot open.
        assertServiceUnavailable(mvc.perform(get("sandbox").cookie(session)).andReturn());
        MvcResult start = mvc.perform(post("sandbox")).andReturn();
        assertServiceUnavailable(start);
        assertThat(start.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
        // Filter path: the tenant registry itself cannot be read.
        assertServiceUnavailable(mvc.perform(get("kpmg")).andReturn());
    }

    private static void assertServiceUnavailable(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        content().json("{\"type\":\"service_unavailable\",\"status\":503}").match(result);
        jsonPath("$.detail").doesNotExist().match(result);
    }

    private static MockHttpServletRequestBuilder post(String tenant) {
        return MockMvcRequestBuilders.post(JOURNEY).contextPath(CONTEXT)
                .header("X-Requested-With", "issuance-portal")
                .with(request -> {
                    request.setServerName(tenant + ".127.0.0.1.nip.io");
                    return request;
                });
    }

    private static MockHttpServletRequestBuilder get(String tenant) {
        return MockMvcRequestBuilders.get(JOURNEY).contextPath(CONTEXT)
                .with(request -> {
                    request.setServerName(tenant + ".127.0.0.1.nip.io");
                    return request;
                });
    }
}
