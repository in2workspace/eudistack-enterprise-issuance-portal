package es.in2.issuanceportal.shared.security;

import es.in2.issuanceportal.shared.tenant.CurrentTenant;
import es.in2.issuanceportal.shared.web.OperationalPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wires the simulated Verifier into a test context: the service reads its keys from {@link VerifierFixture}.
 *
 * <p>Unless the test sets {@code test.auto-token=false}, it also lets every request through the token validation as
 * a holder would: a valid access token for the tenant of the request is added to any request that has none, and the
 * tenant's login settings are fixed, so the tests of the journey keep testing the journey. The tests of the token
 * validation itself turn this off and send their own tokens against the tenant's real configuration.
 */
@TestConfiguration(proxyBeanMethods = false)
public class VerifierTokensConfiguration {

    static final String AUTO_TOKEN = "test.auto-token";

    @Bean
    DynamicPropertyRegistrar verifierInternalUrl() {
        return registry -> registry.add("issuance-portal.token-validation.verifier-internal-url",
                VerifierFixture::internalUrl);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = AUTO_TOKEN, havingValue = "true", matchIfMissing = true)
    TenantLoginSettings fixedLoginSettings() {
        return tenant -> new LoginSettings(AccessTokens.clientOf(tenant), Map.of("doctorid.sd.1", "colegiado"),
                Set.of(AccessTokens.ACR_HIGH));
    }

    @Bean
    @ConditionalOnProperty(name = AUTO_TOKEN, havingValue = "true", matchIfMissing = true)
    FilterRegistrationBean<AutoTokenFilter> autoTokenFilter(Clock clock) {
        FilterRegistrationBean<AutoTokenFilter> registration = new FilterRegistrationBean<>(new AutoTokenFilter(clock));
        // Right after the tenant filter, which resolves the tenant the token is minted for, and before the security chain.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 11);
        return registration;
    }

    static final class AutoTokenFilter extends OncePerRequestFilter {

        private final Clock clock;

        AutoTokenFilter(Clock clock) {
            this.clock = clock;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return OperationalPaths.isOperational(request) || request.getHeader(HttpHeaders.AUTHORIZATION) != null;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String token = AccessTokens.forTenant(CurrentTenant.of(request), clock.instant()).sign();
            chain.doFilter(new WithAuthorization(request, "Bearer " + token), response);
        }
    }

    private static final class WithAuthorization extends HttpServletRequestWrapper {

        private final String value;

        WithAuthorization(HttpServletRequest request, String value) {
            super(request);
            this.value = value;
        }

        @Override
        public String getHeader(String name) {
            return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name) ? value : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)
                    ? Collections.enumeration(List.of(value)) : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            java.util.List<String> names = new java.util.ArrayList<>(Collections.list(super.getHeaderNames()));
            names.add(HttpHeaders.AUTHORIZATION);
            return Collections.enumeration(names);
        }
    }
}
