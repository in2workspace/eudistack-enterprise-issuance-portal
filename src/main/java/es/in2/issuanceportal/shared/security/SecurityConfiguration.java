package es.in2.issuanceportal.shared.security;

import es.in2.issuanceportal.shared.web.OperationalPaths;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The service is an OAuth 2.0 resource server without a session (design.md AD-9): every call of the journey carries
 * its Access Token and is validated on its own. There is no cookie, so there is no CSRF to protect against, and no
 * form login or basic authentication.
 *
 * <p>Protected by default: only the operational paths (health and metrics), {@code GET /config} and
 * {@code POST /incidents} answer without a token. The tenant filter runs before this chain, because the token is
 * validated against the tenant of the request.
 */
@Configuration
@EnableWebSecurity
class SecurityConfiguration {

    private static final String API = "/api/v1";

    /** Health and metrics: no tenant, no token, as they have always been. */
    @Bean
    @Order(1)
    SecurityFilterChain operationalChain(HttpSecurity http) throws Exception {
        statelessAndPlain(http)
                .securityMatcher(OperationalPaths::isOperational)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http, TenantTokenAuthenticator authenticator,
                                 ProblemAuthenticationEntryPoint entryPoint) throws Exception {
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        RequestMatcher withoutToken = new OrRequestMatcher(
                paths.matcher(HttpMethod.GET, API + "/config"),
                paths.matcher(HttpMethod.POST, API + "/incidents"));
        statelessAndPlain(http)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(withoutToken).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(server -> server
                        .authenticationManagerResolver(authenticator)
                        .authenticationEntryPoint(entryPoint))
                .exceptionHandling(handling -> handling.authenticationEntryPoint(entryPoint));
        return http.build();
    }

    /**
     * No session, CSRF, login pages or request cache. The response headers are the service's own
     * ({@code SecurityHeadersFilter}), which also cover the answers of filters that run before this chain.
     */
    private static HttpSecurity statelessAndPlain(HttpSecurity http) throws Exception {
        return http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.disable())
                .headers(headers -> headers.disable())
                .anonymous(Customizer.withDefaults());
    }
}
