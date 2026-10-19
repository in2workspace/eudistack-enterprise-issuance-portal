package es.in2.issuanceportal.shared.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TenantFilterTest {

    private final TenantFilter filter = new TenantFilter(new FixedRegistry(Set.of("sandbox", "cgcom")));

    @Test
    void resolvesTheTenantFromTheFirstSegmentOfTheHost() throws Exception {
        MockHttpServletRequest request = apiRequest("sandbox.127.0.0.1.nip.io");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(CurrentTenant.of((jakarta.servlet.http.HttpServletRequest) chain.getRequest()))
                .isEqualTo(new TenantId("sandbox"));
    }

    @Test
    void prefersTheTenantHeaderNginxSendsForCustomDomains() throws Exception {
        MockHttpServletRequest request = apiRequest("portal.colegiomedico.es");
        request.addHeader(TenantFilter.TENANT_HEADER, "cgcom");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(CurrentTenant.of((jakarta.servlet.http.HttpServletRequest) chain.getRequest()))
                .isEqualTo(new TenantId("cgcom"));
    }

    @Test
    void rejectsAnUnknownTenantWithTenantNotFound() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(apiRequest("unknown.eudistack.net"), response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"type\":\"tenant_not_found\"");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void rejectsAHostWithoutATenantSegment() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(apiRequest("localhost"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void leavesRequestsOutsideTheApiAlone() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/issuance-portal/actuator/health");
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
