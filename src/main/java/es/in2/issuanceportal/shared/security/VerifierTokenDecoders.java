package es.in2.issuanceportal.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import es.in2.issuanceportal.shared.tenant.PublicHostProperties;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * One {@link JwtDecoder} per tenant, in memory, created on the tenant's first token (design.md section 3.1). It checks
 * the signature against the tenant Verifier's JWKS (ES256 only: {@code none}, HMAC and {@code oct} keys never match),
 * the {@code iss}, the time window and the shape of an access token (not an ID token). The claims that come from the
 * portal's own configuration are checked by
 * {@link TenantTokenAuthenticator}.
 *
 * <p>The JWKS is cached and, when a token names a {@code kid} the cache does not have, reloaded once.
 */
@Component
public class VerifierTokenDecoders {

    /** Clock skew accepted on {@code exp} and {@code nbf} (design.md section 3.1). */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private final TokenValidationProperties properties;
    private final PublicHostProperties publicHosts;
    private final ObjectMapper json;
    private final Clock clock;
    private final RestClient http;
    private final ConcurrentMap<TenantId, JwtDecoder> decoders = new ConcurrentHashMap<>();

    VerifierTokenDecoders(TokenValidationProperties properties, PublicHostProperties publicHosts, ObjectMapper json,
                          Clock clock) {
        this.properties = properties;
        this.publicHosts = publicHosts;
        this.json = json;
        this.clock = clock;
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.requestTimeout()).build());
        requests.setReadTimeout(properties.requestTimeout());
        this.http = RestClient.builder().requestFactory(requests).build();
    }

    JwtDecoder forTenant(TenantId tenant) {
        return decoders.computeIfAbsent(tenant, this::create);
    }

    /** Forgets the tenant's decoder and its cached keys: the next token builds them again. */
    public void invalidate(TenantId tenant) {
        decoders.remove(tenant);
    }

    private JwtDecoder create(TenantId tenant) {
        String internalUrl = internalUrl();
        String publicHost = publicHosts.hostOf(tenant);
        VerifierJwksRetriever retriever = new VerifierJwksRetriever(http, json, tenant, publicHost, internalUrl);
        JWKSourceBuilder<SecurityContext> keys = JWKSourceBuilder
                .<SecurityContext>create(retriever.discoveryUrl(), retriever)
                .cache(properties.jwksCacheTtl().toMillis(), properties.requestTimeout().toMillis());
        keys = properties.jwksMinReloadInterval().isZero()
                ? keys.rateLimited(false)
                : keys.rateLimited(properties.jwksMinReloadInterval().toMillis());
        JWKSource<SecurityContext> source = keys.build();

        ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256, source));
        processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT, null));
        // Time and issuer are judged by the validators below, with the skew of this service and not Nimbus' default.
        // The shape of the token is judged here, on the raw claims: Spring fills in a missing iat from exp. Only the
        // Verifier's access token has exp, iat and jti, and only its ID token has a nonce or auth_time: both are
        // signed with the same key and typ, so this tells them apart (RFC 9068 section 4, RFC 8725 section 3.11).
        processor.setJWTClaimsSetVerifier((claims, context) -> {
            if (claims.getExpirationTime() == null || claims.getIssueTime() == null || claims.getJWTID() == null
                    || claims.getClaim("nonce") != null || claims.getClaim("auth_time") != null) {
                throw new BadJWTException("The token does not have the shape of an access token");
            }
        });

        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);
        // The address of the tenant's Verifier as holders and the Verifier itself know it, the same as the
        // audience of the system token's client assertion.
        String issuer = "https://" + publicHost + URI.create(internalUrl).getPath();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(timestamps, new JwtIssuerValidator(issuer)));
        return decoder;
    }

    private String internalUrl() {
        String url = properties.verifierInternalUrl();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
