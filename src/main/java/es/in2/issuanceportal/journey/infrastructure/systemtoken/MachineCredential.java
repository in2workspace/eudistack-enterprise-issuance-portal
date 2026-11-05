package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import com.nimbusds.jose.jwk.ECKey;

import java.util.Objects;

/**
 * The portal's LEARCredentialMachine in one tenant and the private key of its mandatee. Both are secrets:
 * {@link #toString()} shows neither.
 *
 * @param clientId      the mandatee's {@code did:key}, the client of the Verifier
 * @param credentialJwt the credential as the Issuer signed it
 * @param privateKey    the mandatee's key, P-256
 */
record MachineCredential(String clientId, String credentialJwt, ECKey privateKey) {

    MachineCredential {
        Objects.requireNonNull(clientId);
        Objects.requireNonNull(credentialJwt);
        Objects.requireNonNull(privateKey);
    }

    @Override
    public String toString() {
        return "MachineCredential[redacted]";
    }
}
