package es.in2.issuanceportal.journey.infrastructure.issuer;

import es.in2.issuanceportal.journey.application.port.IssuanceApi;
import es.in2.issuanceportal.journey.application.port.SystemTokenProvider;
import es.in2.issuanceportal.shared.tenant.PublicHostProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;

@Slf4j
@Configuration
class IssuerApiConfiguration {

    @Bean
    IssuanceApi issuanceApi(IssuerApiProperties properties, PublicHostProperties publicHosts,
                            SystemTokenProvider systemTokens, Clock clock) {
        log.info("Issuance API at {}, retrying a 503: {}", properties.issuerInternalUrl(), properties.retryOn503());
        // HTTP/1.1: the JDK client's h2c upgrade of a cleartext POST with a body breaks on some servers.
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(properties.requestTimeout()).build());
        requests.setReadTimeout(properties.requestTimeout());
        return new RestClientIssuanceApi(RestClient.builder().requestFactory(requests).build(), properties,
                publicHosts, systemTokens, clock);
    }
}
