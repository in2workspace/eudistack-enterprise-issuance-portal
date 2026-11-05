package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** A machine credential for tests: a fresh P-256 key and a placeholder credential JWT. */
record MachineCredentialFixture(String clientId, String credentialJwt, ECKey key) {

    static MachineCredentialFixture create(String label) {
        try {
            ECKey key = new ECKeyGenerator(Curve.P_256).generate();
            return new MachineCredentialFixture("did:key:" + label, "credential-" + label + ".payload.signature", key);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The content of the tenant's secret. */
    String secretJson() {
        return "{\"clientId\":\"" + clientId + "\",\"credential\":\"" + credentialJwt
                + "\",\"privateKey\":" + key.toJSONString() + "}";
    }

    MachineCredential credential() {
        return new MachineCredential(clientId, credentialJwt, key);
    }

    void writeTo(Path directory, String tenant) throws IOException {
        Files.writeString(directory.resolve(tenant + ".json"), secretJson(), StandardCharsets.UTF_8);
    }
}
