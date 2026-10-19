package es.in2.issuanceportal.shared.web;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestedWithFilterTest {

    private final RequestedWithFilter filter = new RequestedWithFilter();

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"XMLHttpRequest", "", "Issuance-Portal"})
    void aStateChangingRequestWithoutTheExpectedHeaderIsRejected(String header) throws Exception {
        MockHttpServletRequest request = request("POST", "/issuance-portal/api/v1/journey");
        if (header != null) {
            request.addHeader(RequestedWithFilter.HEADER, header);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"type\":\"request_not_allowed\"");
        assertThat(chain.getRequest()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void aStateChangingRequestWithTheHeaderGoesThrough(String method) throws Exception {
        MockHttpServletRequest request = request(method, "/issuance-portal/api/v1/journey");
        request.addHeader(RequestedWithFilter.HEADER, RequestedWithFilter.EXPECTED);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS"})
    void aReadDoesNotNeedTheHeader(String method) throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request(method, "/issuance-portal/api/v1/journey"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/issuance-portal/actuator/health"})
    void requestsOutsideTheApiAreLeftAlone(String path) throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("POST", path), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setContextPath("/issuance-portal");
        return request;
    }
}
