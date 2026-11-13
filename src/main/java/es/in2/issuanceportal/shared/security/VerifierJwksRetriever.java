package es.in2.issuanceportal.shared.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.util.Resource;
import com.nimbusds.jose.util.ResourceRetriever;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

/**
 * Reads the JWKS of a tenant's Verifier the way the Issuer does: the discovery document first, then the JWKS it
 * points to. Both go over the internal network with the tenant and its public host as headers, as the system token
 * does, so the Verifier answers as the tenant's public origin.
 *
 * <p>Only the <em>path</em> of the discovery's {@code jwks_uri} is used: its host is the public one. The path must
 * start with {@code /} and the URL is built on the configured internal origin and checked to keep its host and port,
 * so the document cannot send the portal to another host (for example with {@code @evil.example/keys}).
 */
final class VerifierJwksRetriever implements ResourceRetriever {

    static final String DISCOVERY_PATH = "/.well-known/openid-configuration";

    private final RestClient http;
    private final ObjectMapper json;
    private final TenantId tenant;
    private final String publicHost;
    private final String internalUrl;

    VerifierJwksRetriever(RestClient http, ObjectMapper json, TenantId tenant, String publicHost,
                          String internalUrl) {
        this.http = http;
        this.json = json;
        this.tenant = tenant;
        this.publicHost = publicHost;
        this.internalUrl = internalUrl;
    }

    /** The discovery address; the retriever ignores the URL it is called with and always reads this tenant's. */
    URL discoveryUrl() {
        try {
            return URI.create(internalUrl + DISCOVERY_PATH).toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("verifier-internal-url must be an absolute URL", e);
        }
    }

    @Override
    public Resource retrieveResource(URL ignored) throws IOException {
        try {
            String discovery = get(internalUrl + DISCOVERY_PATH);
            JsonNode jwksUri = json.readTree(discovery).path("jwks_uri");
            if (!jwksUri.isTextual()) {
                throw new IOException("The discovery document has no jwks_uri");
            }
            return new Resource(get(jwksUrl(jwksUri.asText()).toString()), "application/json");
        } catch (RestClientException | IllegalArgumentException | URISyntaxException e) {
            // The message may carry the URL or the body of the answer: keep the kind of failure only.
            throw new IOException("The Verifier did not give its keys: " + e.getClass().getSimpleName());
        }
    }

    private URI jwksUrl(String jwksUri) throws URISyntaxException, IOException {
        String path = URI.create(jwksUri).getPath();
        if (path == null || !path.startsWith("/") || path.startsWith("//")) {
            throw new IOException("The jwks_uri of the discovery has no absolute path");
        }
        URI internal = URI.create(internalUrl);
        URI url = new URI(internal.getScheme(), internal.getRawAuthority(), path, null, null);
        if (!internal.getHost().equals(url.getHost()) || internal.getPort() != url.getPort()) {
            throw new IOException("The jwks_uri of the discovery leaves the internal address");
        }
        return url;
    }

    private String get(String url) {
        return http.get()
                .uri(URI.create(url))
                .accept(MediaType.APPLICATION_JSON)
                .header("X-Tenant", tenant.value())
                .header("X-Forwarded-Host", publicHost)
                .header("X-Forwarded-Proto", "https")
                .retrieve()
                .body(String.class);
    }
}
