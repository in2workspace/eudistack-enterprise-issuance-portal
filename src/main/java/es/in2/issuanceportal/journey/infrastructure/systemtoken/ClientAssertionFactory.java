package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the {@code client_assertion} of a {@code client_credentials} request to the Verifier (RFC 7523 with
 * a presentation): a JWT signed by the machine credential's mandatee, whose {@code vp_token} claim is a
 * self-signed verifiable presentation of the credential. Both carry the signer's public key in the
 * {@code jwk} header, which is the proof of possession the Verifier checks.
 */
final class ClientAssertionFactory {

    private static final Duration PRESENTATION_LIFETIME = Duration.ofMinutes(5);
    private static final Duration ASSERTION_LIFETIME = Duration.ofMinutes(2);

    private final Clock clock;

    ClientAssertionFactory(Clock clock) {
        this.clock = clock;
    }

    /**
     * @param audience the Verifier's public URL, as the Verifier resolves it from the request
     */
    String build(MachineCredential credential, String audience) {
        String presentation = sign(credential, claims(credential, PRESENTATION_LIFETIME)
                .claim("vp", Map.of(
                        "@context", List.of("https://www.w3.org/2018/credentials/v1"),
                        "type", List.of("VerifiablePresentation"),
                        "verifiableCredential", List.of(credential.credentialJwt())))
                .build());
        return sign(credential, claims(credential, ASSERTION_LIFETIME)
                .audience(audience)
                // The Verifier decodes this claim with the standard Base64 alphabet, with padding.
                .claim("vp_token", Base64.getEncoder().encodeToString(presentation.getBytes(StandardCharsets.US_ASCII)))
                .build());
    }

    private JWTClaimsSet.Builder claims(MachineCredential credential, Duration lifetime) {
        Instant now = clock.instant();
        return new JWTClaimsSet.Builder()
                .issuer(credential.clientId())
                .subject(credential.clientId())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(lifetime)))
                .jwtID(UUID.randomUUID().toString());
    }

    private static String sign(MachineCredential credential, JWTClaimsSet claims) {
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(JOSEObjectType.JWT)
                .jwk(credential.privateKey().toPublicJWK())
                .build();
        try {
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(new ECDSASigner(credential.privateKey()));
            return jwt.serialize();
        } catch (JOSEException e) {
            // The key was checked when the secret was read; the message could quote key material.
            throw new IllegalStateException("The machine credential's key cannot sign");
        }
    }
}
