package es.in2.issuanceportal.shared.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * The two filters that read the proxies' headers, in a fixed order: first the client's address, then
 * Spring's {@code ForwardedHeaderFilter} for the public host and scheme (the tenant comes from the host).
 * Registered here instead of with {@code server.forward-headers-strategy: framework}, which would give
 * {@code ForwardedHeaderFilter} the same order as {@link ClientAddressFilter} and the leftmost address.
 */
@Configuration
class ForwardedHeadersConfiguration {

    @Bean
    FilterRegistrationBean<ClientAddressFilter> clientAddressFilter(
            @Value("${issuance-portal.client-address.trusted-proxies}") int trustedProxies) {
        FilterRegistrationBean<ClientAddressFilter> registration =
                new FilterRegistrationBean<>(new ClientAddressFilter(trustedProxies));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    FilterRegistrationBean<ForwardedHeaderFilter> forwardedHeaderFilter() {
        FilterRegistrationBean<ForwardedHeaderFilter> registration =
                new FilterRegistrationBean<>(new ForwardedHeaderFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
