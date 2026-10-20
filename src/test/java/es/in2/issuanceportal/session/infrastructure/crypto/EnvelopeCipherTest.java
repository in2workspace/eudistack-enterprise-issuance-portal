package es.in2.issuanceportal.session.infrastructure.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class EnvelopeCipherTest {

    private static final byte[] CONTEXT = "journey-1/identity".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PLAINTEXT = "{\"givenName\":\"Ana\",\"dni\":\"12345678Z\"}".getBytes(StandardCharsets.UTF_8);

    private final EnvelopeCipher cipher = new EnvelopeCipher(new LocalDataKeyProvider(randomKey()));

    @Test
    void decrypt_sealedValue_returnsPlaintext() {
        assertThat(cipher.decrypt(cipher.encrypt(PLAINTEXT, CONTEXT), CONTEXT)).isEqualTo(PLAINTEXT);
    }

    @Test
    void encrypt_personalData_doesNotContainPlaintext() {
        String sealed = new String(cipher.encrypt(PLAINTEXT, CONTEXT), StandardCharsets.ISO_8859_1);

        assertThat(sealed).doesNotContain("12345678Z").doesNotContain("Ana");
    }

    @Test
    void encrypt_sameValueTwice_givesDifferentResults() {
        assertThat(cipher.encrypt(PLAINTEXT, CONTEXT)).isNotEqualTo(cipher.encrypt(PLAINTEXT, CONTEXT));
    }

    @Test
    void decrypt_tamperedValue_throws() {
        byte[] sealed = cipher.encrypt(PLAINTEXT, CONTEXT);
        sealed[sealed.length - 1] ^= 1;

        assertThatIllegalArgumentException().isThrownBy(() -> cipher.decrypt(sealed, CONTEXT));
    }

    @Test
    void decrypt_otherContext_throws() {
        byte[] sealed = cipher.encrypt(PLAINTEXT, CONTEXT);

        assertThatIllegalArgumentException().isThrownBy(() ->
                cipher.decrypt(sealed, "journey-2/identity".getBytes(StandardCharsets.UTF_8)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                cipher.decrypt(sealed, "journey-1/email".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void decrypt_otherKey_throws() {
        byte[] sealed = new EnvelopeCipher(new LocalDataKeyProvider(randomKey())).encrypt(PLAINTEXT, CONTEXT);

        assertThatIllegalArgumentException().isThrownBy(() -> cipher.decrypt(sealed, CONTEXT));
    }

    @Test
    void decrypt_truncatedValue_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> cipher.decrypt(new byte[]{1, 0}, CONTEXT));
    }

    @Test
    void localDataKeyProvider_keyNot32Bytes_throws() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new LocalDataKeyProvider(Base64.getEncoder().encodeToString(new byte[16])));
        assertThatIllegalArgumentException().isThrownBy(() -> new LocalDataKeyProvider(null));
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
