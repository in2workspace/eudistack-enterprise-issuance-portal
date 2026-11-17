package es.in2.issuanceportal.shared.web;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class OperationalPathsTest {

    @ParameterizedTest
    @ValueSource(strings = {"/issuance-portal/health", "/issuance-portal/health/readiness",
            "/issuance-portal/info", "/issuance-portal/prometheus"})
    void isOperational_healthAndMetrics_returnsTrue(String path) {
        assertThat(OperationalPaths.isOperational(request(path))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/issuance-portal/%61pi/v1/journey", "/issuance-portal/api;x/v1/journey",
            "/issuance-portal/api/v1/journey;jsessionid=1", "/issuance-portal/new-endpoint",
            "/issuance-portal/health/../api/v1/journey", "/issuance-portal/health/%2e%2e/api/v1/journey",
            "/issuance-portal/./health", "/issuance-portal/actuator/health",
            "/issuance-portal/actuator/../api/v1/journey", "/issuance-portal/actuator/%2e%2e/api/v1/journey",
            "/issuance-portal/actuator/./health"})
    void isOperational_encodedPathOrNewEndpoint_returnsFalse(String path) {
        assertThat(OperationalPaths.isOperational(request(path))).isFalse();
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContextPath("/issuance-portal");
        return request;
    }
}
