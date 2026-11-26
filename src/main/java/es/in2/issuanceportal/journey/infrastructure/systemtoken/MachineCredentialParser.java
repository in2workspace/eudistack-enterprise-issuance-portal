package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.eudistack.verifierclient.credential.MachineCredential;
import net.eudistack.verifierclient.jwt.EcKeyLoader;

import java.util.Optional;

/**
 * Reads the content of a tenant's secret:
 * {@code {"credential": "<jwt>", "privateKey": <P-256 JWK object, or hex scalar string>}}. The client is
 * derived from the credential, so a {@code clientId} in older secrets is ignored.
 * Whatever is wrong with it, the result is only "no usable credential": the reason may quote the secret.
 */
final class MachineCredentialParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MachineCredentialParser() {
    }

    static Optional<MachineIdentity> parse(String content) {
        try {
            JsonNode root = JSON.readTree(content);
            JsonNode credential = root.get("credential");
            JsonNode key = root.get("privateKey");
            if (credential == null || !credential.isTextual() || key == null || !(key.isObject() || key.isTextual())) {
                return Optional.empty();
            }
            return parse(credential.asText(), key.isObject() ? key.toString() : key.asText());
        } catch (Exception _) {
            return Optional.empty();
        }
    }

    /**
     * @param credential the machine credential's JWT
     * @param privateKey the mandatee's P-256 key, as a JWK or a hex scalar
     */
    static Optional<MachineIdentity> parse(String credential, String privateKey) {
        try {
            return Optional.of(new MachineIdentity(
                    MachineCredential.parse(credential), EcKeyLoader.loadPrivateKey(privateKey)));
        } catch (Exception _) {
            return Optional.empty();
        }
    }
}
