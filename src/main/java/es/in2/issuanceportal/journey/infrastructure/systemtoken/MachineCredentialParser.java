package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;

import java.util.Optional;

/**
 * Reads the content of a tenant's secret:
 * {@code {"clientId": "did:key:...", "credential": "<jwt>", "privateKey": {<JWK, P-256, with d>}}}.
 * Whatever is wrong with it, the result is only "no usable credential": the reason may quote the secret.
 */
final class MachineCredentialParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MachineCredentialParser() {
    }

    static Optional<MachineCredential> parse(String content) {
        try {
            JsonNode root = JSON.readTree(content);
            String clientId = text(root, "clientId");
            String credential = text(root, "credential");
            JsonNode key = root.get("privateKey");
            if (clientId == null || credential == null || key == null || !key.isObject()) {
                return Optional.empty();
            }
            ECKey privateKey = ECKey.parse(key.toString());
            if (!privateKey.isPrivate() || !Curve.P_256.equals(privateKey.getCurve())) {
                return Optional.empty();
            }
            return Optional.of(new MachineCredential(clientId, credential, privateKey));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }
}
