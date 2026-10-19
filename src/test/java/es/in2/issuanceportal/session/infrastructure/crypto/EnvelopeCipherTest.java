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
    void decryptsWhatItEncrypted() {
        assertThat(cipher.decrypt(cipher.encrypt(PLAINTEXT, CONTEXT), CONTEXT)).isEqualTo(PLAINTEXT);
    }

    @Test
    void theSealedValueDoesNotContainThePlaintext() {
        String sealed = new String(cipher.encrypt(PLAINTEXT, CONTEXT), StandardCharsets.ISO_8859_1);

        assertThat(sealed).doesNotContain("12345678Z").doesNotContain("Ana");
    }

    @Test
    void encryptingTheSameValueTwiceGivesDifferentResults() {
        assertThat(cipher.encrypt(PLAINTEXT, CONTEXT)).isNotEqualTo(cipher.encrypt(PLAINTEXT, CONTEXT));
    }

    @Test
    void aTamperedValueIsRejected() {
        byte[] sealed = cipher.encrypt(PLAINTEXT, CONTEXT);
        sealed[sealed.length - 1] ^= 1;

        assertThatIllegalArgumentException().isThrownBy(() -> cipher.decrypt(sealed, CONTEXT));
    }

    @Test
    void aValueMovedToAnotherJourneyOrColumnIsRejected() {
        byte[] sealed = cipher.encrypt(PLAINTEXT, CONTEXT);

        assertThatIllegalArgumentException().isThrownBy(() ->
                cipher.decrypt(sealed, "journey-2/identity".getBytes(StandardCharsets.UTF_8)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                cipher.decrypt(sealed, "journey-1/email".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aValueEncryptedWithAnotherKeyIsRejected() {
        byte[] sealed = new EnvelopeCipher(new LocalDataKeyProvider(randomKey())).encrypt(PLAINTEXT, CONTEXT);

        assertThatIllegalArgumentException().isThrownBy(() -> cipher.decrypt(sealed, CONTEXT));
    }

    @Test
    void aTruncatedValueIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> cipher.decrypt(new byte[]{1, 0}, CONTEXT));
    }

    @Test
    void theLocalKeyMustBe32Bytes() {
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
