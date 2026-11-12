package es.in2.issuanceportal.shared.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mints access tokens as the tenant's Verifier would, for tests: by default valid for the tenant, with a certificate
 * login of high assurance, and with whatever claim a scenario changes. Signed with {@link VerifierFixture#signingKey()}
 * unless the scenario says otherwise.
 */
public final class AccessTokens {

    public static final String ACR_HIGH = "http://eidas.europa.eu/LoA/high";
    public static final String ACR_SUBSTANTIAL = "http://eidas.europa.eu/LoA/substantial";
    public static final String GIVEN_NAME = "Lucía";
    public static final String FAMILY_NAME = "Prueba Sintética";
    public static final String PERSONAL_NUMBER = "00000001R";

    private final Map<String, Object> claims = new LinkedHashMap<>();
    private ECKey key = VerifierFixture.signingKey();
    private String keyId = VerifierFixture.KEY_ID;

    private AccessTokens(TenantId tenant, Instant now) {
        claims.put("iss", issuerOf(tenant));
        claims.put("sub", "holder-" + UUID.randomUUID());
        claims.put("aud", List.of(clientOf(tenant)));
        claims.put("tenant", tenant.value());
        claims.put("acr", ACR_HIGH);
        claims.put("amr", List.of("hwk"));
        claims.put("given_name", GIVEN_NAME);
        claims.put("family_name", FAMILY_NAME);
        claims.put("personal_administrative_number", PERSONAL_NUMBER);
        claims.put("iat", now);
        claims.put("nbf", now);
        claims.put("exp", now.plus(Duration.ofMinutes(15)));
    }

    /** A token valid for the tenant as of {@code now}. */
    public static AccessTokens forTenant(TenantId tenant, Instant now) {
        return new AccessTokens(tenant, now);
    }

    public static String issuerOf(TenantId tenant) {
        return "https://" + tenant.value() + ".127.0.0.1.nip.io:4443/verifier";
    }

    public static String clientOf(TenantId tenant) {
        return "issuance-portal-" + tenant.value();
    }

    public AccessTokens claim(String name, Object value) {
        claims.put(name, value);
        return this;
    }

    public AccessTokens without(String name) {
        claims.remove(name);
        return this;
    }

    public AccessTokens signedWith(ECKey signingKey) {
        this.key = signingKey;
        this.keyId = signingKey.getKeyID();
        return this;
    }

    /** The compact ES256 token. */
    public String sign() {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId).build(), claimsSet());
            jwt.sign(new ECDSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The same claims with {@code alg: none}. */
    public String unsecured() {
        return new PlainJWT(claimsSet()).serialize();
    }

    /** The same claims signed with HMAC under a secret the Verifier's JWKS may carry as an {@code oct} key. */
    public String hmacSigned(byte[] secret, String keyId) {
        try {
            JWSSigner signer = new MACSigner(secret);
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(keyId).build(), claimsSet());
            jwt.sign(signer);
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The subject of the token, to assert it is never logged. */
    public String subject() {
        return (String) claims.get("sub");
    }

    private JWTClaimsSet claimsSet() {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
        claims.forEach((name, value) -> builder.claim(name, value instanceof Instant instant ? Date.from(instant) : value));
        return builder.build();
    }
}
