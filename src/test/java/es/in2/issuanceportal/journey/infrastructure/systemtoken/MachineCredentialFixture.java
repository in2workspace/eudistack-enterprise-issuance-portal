package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A machine credential for tests: a LEARCredentialMachine-shaped JWT whose {@code cnf} is a fresh P-256 key,
 * and the key that goes in the secret, which is that same key unless the fixture is a mismatched one.
 */
public record MachineCredentialFixture(String clientId, String credentialJwt, ECKey key) {

    public static MachineCredentialFixture create(String label) {
        ECKey key = generate();
        return new MachineCredentialFixture("did:key:" + label, credential(label, key), key);
    }

    /** The secret holds a key that is not the credential's {@code cnf}. */
    static MachineCredentialFixture createMismatched(String label) {
        return new MachineCredentialFixture("did:key:" + label, credential(label, generate()), generate());
    }

    private static ECKey generate() {
        try {
            return new ECKeyGenerator(Curve.P_256).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String credential(String label, ECKey boundKey) {
        Map<String, Object> mandate = Map.of(
                "mandatee", Map.of("id", "did:key:" + label),
                "power", List.of(Map.of("domain", label, "function", "Onboarding")));
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("did:elsi:issuer")
                .claim("vc", Map.of("credentialSubject", Map.of("mandate", mandate)))
                .claim("cnf", Map.of("jwk", boundKey.toPublicJWK().toJSONObject()))
                .build();
        try {
            // Whoever signed it is irrelevant here: the portal only presents the credential.
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.ES256), claims);
            jwt.sign(new ECDSASigner(generate()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The content of the tenant's secret. */
    public String secretJson() {
        return "{\"credential\":\"" + credentialJwt + "\",\"privateKey\":" + key.toJSONString() + "}";
    }

    public void writeTo(Path directory, String tenant) throws IOException {
        Files.writeString(directory.resolve(tenant + ".json"), secretJson(), StandardCharsets.UTF_8);
    }
}
