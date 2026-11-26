package es.in2.issuanceportal.shared.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentTenantTest {

    private static final TenantId SANDBOX = new TenantId("sandbox");

    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @Nested
    @DisplayName("find")
    class Find {

        @Test
        void find_resolvedRequest_returnsTenant() {
            // Arrange
            TestTenants.resolved(request, SANDBOX);

            // Act
            Optional<TenantId> tenant = CurrentTenant.find(request);

            // Assert
            assertThat(tenant).contains(SANDBOX);
        }

        @Test
        void find_requestWithoutTenant_returnsEmpty() {
            // Arrange
            request.setRequestURI("/health");

            // Act
            Optional<TenantId> tenant = CurrentTenant.find(request);

            // Assert
            assertThat(tenant).isEmpty();
        }
    }

    @Nested
    @DisplayName("of")
    class Of {

        @Test
        void of_requestWithoutTenant_throwsIllegalState() {
            // Arrange
            request.setRequestURI("/health");

            // Act & Assert
            assertThatThrownBy(() -> CurrentTenant.of(request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("No tenant resolved for this request");
        }
    }
}
