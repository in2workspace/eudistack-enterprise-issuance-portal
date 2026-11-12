package es.in2.issuanceportal.shared.security;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;

import java.util.Arrays;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/**
 * A simulated Verifier for the whole test JVM: its discovery document and its JWKS over HTTP, and the P-256 key its
 * access tokens are signed with. One server for all the Spring contexts of the run; a test that changes what it
 * serves puts it back with {@link #reset()}.
 *
 * <p>The discovery's {@code jwks_uri} carries a public host that nobody listens on, as the real one does: the portal
 * must read the keys from the internal address and ignore that host.
 */
public final class VerifierFixture {

    public static final String DISCOVERY_PATH = "/verifier/.well-known/openid-configuration";
    public static final String JWKS_PATH = "/verifier/oidc/jwks";
    public static final String KEY_ID = "verifier-key-1";

    private static final WireMockServer WIRE = start();
    private static final ECKey SIGNING_KEY = generate(KEY_ID);

    static {
        reset();
    }

    private VerifierFixture() {
    }

    /** The Verifier as the portal reaches it: scheme, host, port and context path. */
    public static String internalUrl() {
        return "http://localhost:" + WIRE.port() + "/verifier";
    }

    public static WireMockServer wire() {
        return WIRE;
    }

    /** The key access tokens are signed with; its public part is in the default JWKS. */
    public static ECKey signingKey() {
        return SIGNING_KEY;
    }

    public static ECKey newKey(String keyId) {
        return generate(keyId);
    }

    /** Back to the default: a working discovery and a JWKS with {@link #signingKey()} only; no recorded requests. */
    public static void reset() {
        WIRE.resetAll();
        stubDiscovery();
        publish(SIGNING_KEY);
    }

    /** Serves a JWKS with these keys, public parts only for the asymmetric ones. */
    public static void publish(JWK... keys) {
        JWK[] publicKeys = Arrays.stream(keys).map(key -> key.toPublicJWK() == null ? key : key.toPublicJWK())
                .toArray(JWK[]::new);
        WIRE.stubFor(get(urlPathEqualTo(JWKS_PATH)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(new JWKSet(Arrays.asList(publicKeys)).toString(false))));
    }

    public static void stubDiscovery() {
        WIRE.stubFor(get(urlPathEqualTo(DISCOVERY_PATH)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"issuer\":\"https://sandbox.127.0.0.1.nip.io:4443/verifier\","
                        + "\"jwks_uri\":\"https://sandbox.127.0.0.1.nip.io:4443/verifier/oidc/jwks\"}")));
    }

    private static WireMockServer start() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        return server;
    }

    private static ECKey generate(String keyId) {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
