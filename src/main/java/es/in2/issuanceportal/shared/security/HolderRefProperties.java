package es.in2.issuanceportal.shared.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Base64;

/**
 * The key of the holder reference (design.md §3.2), one per environment. There is no default: a service without it, or
 * with a key shorter than the 32 bytes of the HMAC's hash, does not start.
 *
 * @param key base64, at least 32 bytes once decoded
 */
@Validated
@ConfigurationProperties("issuance-portal.holder-ref")
public record HolderRefProperties(@NotBlank String key) {

    static final int MIN_KEY_BYTES = 32;

    public HolderRefProperties {
        if (key != null && !key.isBlank()) {
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(key.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("issuance-portal.holder-ref.key must be base64");
            }
            if (bytes.length < MIN_KEY_BYTES) {
                throw new IllegalArgumentException(
                        "issuance-portal.holder-ref.key must be at least " + MIN_KEY_BYTES + " bytes");
            }
        }
    }

    byte[] keyBytes() {
        return Base64.getDecoder().decode(key.trim());
    }

    @Override
    public String toString() {
        return "HolderRefProperties[key=redacted]";
    }
}
