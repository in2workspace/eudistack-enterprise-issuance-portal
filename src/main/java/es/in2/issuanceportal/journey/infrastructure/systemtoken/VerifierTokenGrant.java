package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import es.in2.issuanceportal.journey.application.port.SystemToken;
import es.in2.issuanceportal.journey.application.port.SystemTokenUnavailableException;
import es.in2.issuanceportal.journey.application.port.SystemTokenUnavailableException.Reason;
import es.in2.issuanceportal.shared.tenant.PublicHostProperties;
import es.in2.issuanceportal.shared.tenant.TenantId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.Clock;

/**
 * {@code client_credentials} against the Verifier of the tenant, over the internal network. The call carries
 * the tenant ({@code X-Tenant}) and the tenant's public host and scheme as forwarded headers, so the Verifier
 * resolves its public origin: that is the {@code iss} of the token and the {@code aud} of the assertion.
 *
 * <p>Nothing of the credential, the assertion or the token is logged, and no cause is kept: a failure
 * only becomes a {@link Reason}.
 */
@Slf4j
final class VerifierTokenGrant implements TokenGrant {

    static final String CLIENT_ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    private static final String TOKEN_PATH = "/oidc/token";

    private final MachineCredentialSource credentials;
    private final ClientAssertionFactory assertions;
    private final RestClient http;
    private final Clock clock;
    private final String verifierInternalUrl;
    private final PublicHostProperties publicHosts;

    VerifierTokenGrant(MachineCredentialSource credentials, ClientAssertionFactory assertions, RestClient http,
                       Clock clock, String verifierInternalUrl, PublicHostProperties publicHosts) {
        this.credentials = credentials;
        this.assertions = assertions;
        this.http = http;
        this.clock = clock;
        this.verifierInternalUrl = stripTrailingSlash(verifierInternalUrl);
        this.publicHosts = publicHosts;
    }

    @Override
    public SystemToken request(TenantId tenant) {
        MachineIdentity identity = credentials.find(tenant).orElseThrow(() -> {
            log.warn("No usable machine credential for tenant {}", tenant);
            return new SystemTokenUnavailableException(Reason.NO_MACHINE_CREDENTIAL);
        });
        String publicHost = publicHosts.hostOf(tenant);
        String publicVerifierUrl = "https://" + publicHost + URI.create(verifierInternalUrl).getPath();

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", identity.credential().clientId());
        form.add("client_assertion_type", CLIENT_ASSERTION_TYPE);
        form.add("client_assertion", assertions.build(identity, publicVerifierUrl));

        TokenResponse response = post(tenant, publicHost, form);
        if (response == null || response.accessToken() == null || response.accessToken().isBlank()
                || response.expiresIn() == null || response.expiresIn() <= 0) {
            log.warn("The Verifier of tenant {} answered without a usable token", tenant);
            throw new SystemTokenUnavailableException(Reason.VERIFIER_REJECTED);
        }
        log.info("System token obtained for tenant {}, valid for {} s", tenant, response.expiresIn());
        return new SystemToken(response.accessToken(), clock.instant().plusSeconds(response.expiresIn()));
    }

    private TokenResponse post(TenantId tenant, String publicHost, MultiValueMap<String, String> form) {
        try {
            return http.post()
                    .uri(verifierInternalUrl + TOKEN_PATH)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("X-Tenant", tenant.value())
                    .header("X-Forwarded-Host", publicHost)
                    .header("X-Forwarded-Proto", "https")
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
        } catch (RestClientException e) {
            log.warn("The Verifier of tenant {} sent a token response that cannot be read", tenant);
            throw new SystemTokenUnavailableException(Reason.VERIFIER_REJECTED);
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
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
