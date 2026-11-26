package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import es.in2.issuanceportal.shared.systemtoken.SystemToken;
import es.in2.issuanceportal.shared.systemtoken.SystemTokenUnavailableException;
import es.in2.issuanceportal.shared.systemtoken.SystemTokenUnavailableException.Reason;
import es.in2.issuanceportal.shared.tenant.TenantId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Clock;
import java.util.function.Consumer;

/**
 * A {@code client_credentials} request with a {@code client_assertion} to the token endpoint of a tenant's
 * Verifier, which the tenant ({@code X-Tenant}) names.
 *
 * <p>Nothing of the assertion or the token is logged, and no cause is kept: a failure only becomes a
 * {@link Reason}.
 */
@Slf4j
@RequiredArgsConstructor
final class VerifierTokenEndpoint {

    static final String CLIENT_ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    private final RestClient http;
    private final Clock clock;

    /**
     * @param headers what the call carries besides the tenant
     * @throws SystemTokenUnavailableException when the Verifier does not grant a usable token
     */
    SystemToken request(TenantId tenant, String tokenUrl, String clientId, String assertion,
                        Consumer<HttpHeaders> headers) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_assertion_type", CLIENT_ASSERTION_TYPE);
        form.add("client_assertion", assertion);

        TokenResponse response = post(tenant, tokenUrl, headers, form);
        if (response == null || response.accessToken() == null || response.accessToken().isBlank()
                || response.expiresIn() == null || response.expiresIn() <= 0) {
            log.warn("The Verifier of tenant {} answered without a usable token", tenant);
            throw new SystemTokenUnavailableException(Reason.VERIFIER_REJECTED);
        }
        log.info("System token obtained for tenant {}, valid for {} s", tenant, response.expiresIn());
        return new SystemToken(response.accessToken(), clock.instant().plusSeconds(response.expiresIn()));
    }

    private TokenResponse post(TenantId tenant, String tokenUrl, Consumer<HttpHeaders> headers,
                               MultiValueMap<String, String> form) {
        try {
            return http.post()
                    .uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("X-Tenant", tenant.value())
                    .headers(headers)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (RestClientResponseException e) {
            boolean rejected = e.getStatusCode().is4xxClientError();
            log.warn("The Verifier of tenant {} answered {} to the token request", tenant, e.getStatusCode().value());
            throw new SystemTokenUnavailableException(rejected ? Reason.VERIFIER_REJECTED : Reason.VERIFIER_UNREACHABLE);
        } catch (ResourceAccessException e) {
            log.warn("The Verifier of tenant {} did not answer the token request: {}", tenant,
                    e.getCause() == null ? "I/O error" : e.getCause().getClass().getSimpleName());
            throw new SystemTokenUnavailableException(Reason.VERIFIER_UNREACHABLE);
        } catch (RestClientException _) {
            log.warn("The Verifier of tenant {} sent a token response that cannot be read", tenant);
            throw new SystemTokenUnavailableException(Reason.VERIFIER_REJECTED);
        }
    }

    /** The token response; the token is a secret, so it is not in {@code toString()}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("access_token") String accessToken,
                         @JsonProperty("expires_in") Long expiresIn) {

        @Override
        public String toString() {
            return "TokenResponse[expiresIn=" + expiresIn + "]";
        }
    }
}
