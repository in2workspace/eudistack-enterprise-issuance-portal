package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import com.nimbusds.jose.jwk.ECKey;
import net.eudistack.verifierclient.credential.MachineCredential;

import java.util.Objects;

/**
 * The portal's LEARCredentialMachine in one tenant and the private key of its mandatee. Both are secrets:
 * {@link #toString()} shows neither.
 *
 * @param credential the credential as the Issuer signed it, parsed by the verifier client SDK
 * @param privateKey the mandatee's key, P-256
 */
record MachineIdentity(MachineCredential credential, ECKey privateKey) {

    MachineIdentity {
        Objects.requireNonNull(credential);
        Objects.requireNonNull(privateKey);
    }

    @Override
    public String toString() {
        return "MachineIdentity[redacted]";
    }
}
