package es.in2.issuanceportal.session.infrastructure.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * A fixed key from configuration, for Docker Compose and tests, where there is no KMS. Never for an
 * AWS environment.
 */
public class LocalDataKeyProvider implements DataKeyProvider {

    private static final byte[] MARKER = "local".getBytes(StandardCharsets.US_ASCII);
    private static final int KEY_BYTES = 32;

    private final DataKey key;

    public LocalDataKeyProvider(String base64Key) {
        byte[] plaintext = base64Key == null ? new byte[0] : Base64.getDecoder().decode(base64Key);
        if (plaintext.length != KEY_BYTES) {
            throw new IllegalArgumentException("The local data key must be 32 bytes in base64");
        }
        this.key = new DataKey(plaintext, MARKER);
    }

    @Override
    public DataKey currentKey() {
        return key;
    }

    @Override
    public byte[] unwrap(byte[] wrapped) {
        if (!MessageDigest.isEqual(MARKER, wrapped)) {
            throw new IllegalArgumentException("Value was not encrypted with the local key");
        }
        return key.plaintext();
    }
}
