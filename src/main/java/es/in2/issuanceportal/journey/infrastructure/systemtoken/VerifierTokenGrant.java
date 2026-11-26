package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import es.in2.issuanceportal.shared.systemtoken.SystemToken;
import es.in2.issuanceportal.shared.systemtoken.SystemTokenUnavailableException;
import es.in2.issuanceportal.shared.systemtoken.SystemTokenUnavailableException.Reason;
import es.in2.issuanceportal.shared.tenant.PublicHostProperties;
import es.in2.issuanceportal.shared.tenant.TenantId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

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

    private static final String TOKEN_PATH = "/oidc/token";

    private final MachineCredentialSource credentials;
    private final ClientAssertionFactory assertions;
    private final VerifierTokenEndpoint endpoint;
    private final String verifierInternalUrl;
    private final PublicHostProperties publicHosts;

    VerifierTokenGrant(MachineCredentialSource credentials, ClientAssertionFactory assertions, RestClient http,
                       Clock clock, String verifierInternalUrl, PublicHostProperties publicHosts) {
        this.credentials = credentials;
        this.assertions = assertions;
        this.endpoint = new VerifierTokenEndpoint(http, clock);
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

        return endpoint.request(tenant, verifierInternalUrl + TOKEN_PATH, identity.credential().clientId(),
                assertions.build(identity, publicVerifierUrl), headers -> {
                    headers.set("X-Forwarded-Host", publicHost);
                    headers.set("X-Forwarded-Proto", "https");
                });
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
