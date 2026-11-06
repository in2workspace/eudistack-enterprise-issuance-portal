package es.in2.issuanceportal.journey.infrastructure.walletunit;

import es.in2.issuanceportal.journey.application.port.OnboardingTickets;
import es.in2.issuanceportal.journey.application.port.SystemTokenProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;

@Configuration
class WalletUnitServiceConfiguration {

    @Bean
    OnboardingTickets onboardingTickets(WalletUnitServiceProperties properties, SystemTokenProvider systemTokens,
                                        Clock clock) {
        // HTTP/1.1: the JDK client's h2c upgrade of a cleartext POST with a body breaks on some servers.
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(properties.requestTimeout()).build());
        requests.setReadTimeout(properties.requestTimeout());
        return new RestClientOnboardingTickets(RestClient.builder().requestFactory(requests).build(), systemTokens,
                clock);
    }
}
