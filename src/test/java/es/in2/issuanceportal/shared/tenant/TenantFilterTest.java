package es.in2.issuanceportal.shared.tenant;

import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TenantFilterTest {

    private final TenantFilter filter = new TenantFilter(new FixedRegistry(Set.of("sandbox", "cgcom")), tenant -> { });

    @Test
    void doFilter_canonicalHost_resolvesTenantFromFirstSegment() throws Exception {
        MockHttpServletRequest request = apiRequest("sandbox.127.0.0.1.nip.io");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(CurrentTenant.of((jakarta.servlet.http.HttpServletRequest) chain.getRequest()))
                .isEqualTo(new TenantId("sandbox"));
    }

    @Test
    void doFilter_tenantHeader_prefersHeaderOverHost() throws Exception {
        MockHttpServletRequest request = apiRequest("portal.colegiomedico.es");
        request.addHeader(TenantFilter.TENANT_HEADER, "cgcom");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(CurrentTenant.of((jakarta.servlet.http.HttpServletRequest) chain.getRequest()))
                .isEqualTo(new TenantId("cgcom"));
    }

    @Test
    void doFilter_unknownTenant_returns404TenantNotFound() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(apiRequest("unknown.eudistack.net"), response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"type\":\"tenant_not_found\"");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("AC-08 — doFilter_activeTenant_ensuresItsSchemaBeforeGoingOn")
    void doFilter_activeTenant_ensuresItsSchemaBeforeGoingOn() throws Exception {
        List<TenantId> ensured = new ArrayList<>();
        TenantFilter recording = new TenantFilter(new FixedRegistry(Set.of("sandbox")), ensured::add);
        MockFilterChain chain = new MockFilterChain();

        recording.doFilter(apiRequest("sandbox.127.0.0.1.nip.io"), new MockHttpServletResponse(), chain);

        assertThat(ensured).containsExactly(new TenantId("sandbox"));
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("AC-08 — doFilter_unknownOrInactiveTenant_neverCreatesASchema")
    void doFilter_unknownOrInactiveTenant_neverCreatesASchema() throws Exception {
        List<TenantId> ensured = new ArrayList<>();
        TenantFilter recording = new TenantFilter(new FixedRegistry(Set.of("sandbox")), ensured::add);

        recording.doFilter(apiRequest("attacker.eudistack.net"), new MockHttpServletResponse(), new MockFilterChain());
        MockHttpServletRequest withHeader = apiRequest("sandbox.127.0.0.1.nip.io");
        withHeader.addHeader(TenantFilter.TENANT_HEADER, "pg_catalog");
        recording.doFilter(withHeader, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(ensured).isEmpty();
    }

    @Test
    void doFilter_schemaCannotBeCreated_returns503ServiceUnavailable() throws Exception {
        TenantFilter failing = new TenantFilter(new FixedRegistry(Set.of("sandbox")), tenant -> {
            throw new FlywayException("migration failed");
        });
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        failing.doFilter(apiRequest("sandbox.127.0.0.1.nip.io"), response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("\"type\":\"service_unavailable\"");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void doFilter_hostWithoutTenantSegment_returns404() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(apiRequest("localhost"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void doFilter_encodedApiPath_stillRequiresTenant() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/issuance-portal/%61pi/v1/journey");
        request.setContextPath("/issuance-portal");
        request.setServerName("unknown.eudistack.net");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void doFilter_healthPath_passesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/issuance-portal/health");
        request.setContextPath("/issuance-portal");
        request.setServerName("localhost");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    private static MockHttpServletRequest apiRequest(String host) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/issuance-portal/api/v1/journey");
        request.setContextPath("/issuance-portal");
        request.setServerName(host);
        return request;
    }

    private record FixedRegistry(Set<String> active) implements TenantRegistry {

        @Override
        public boolean isActive(TenantId tenant) {
            return active.contains(tenant.value());
        }

        @Override
        public List<TenantId> activeTenants() {
            return active.stream().map(TenantId::new).toList();
        }
    }
}
