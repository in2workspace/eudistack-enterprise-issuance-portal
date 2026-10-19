package es.in2.issuanceportal.session.infrastructure.crypto;

import es.in2.issuanceportal.session.application.port.PersonalDataCipher;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * AES-256-GCM envelope encryption. Every value carries the wrapped data key it was encrypted with, so
 * any instance can decrypt what another one wrote:
 * {@code version (1) | wrapped key length (2) | wrapped key | nonce (12) | ciphertext and tag}.
 */
public class EnvelopeCipher implements PersonalDataCipher {

    private static final byte VERSION = 1;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final DataKeyProvider keys;
    private final SecureRandom random = new SecureRandom();

    public EnvelopeCipher(DataKeyProvider keys) {
        this.keys = keys;
    }

    @Override
    public byte[] encrypt(byte[] plaintext, byte[] context) {
        DataKey key = keys.currentKey();
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] ciphertext = run(Cipher.ENCRYPT_MODE, key.plaintext(), nonce, context, plaintext);
        return ByteBuffer.allocate(1 + 2 + key.wrapped().length + NONCE_BYTES + ciphertext.length)
                .put(VERSION)
                .putShort((short) key.wrapped().length)
                .put(key.wrapped())
                .put(nonce)
                .put(ciphertext)
                .array();
    }

    @Override
    public byte[] decrypt(byte[] sealed, byte[] context) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(sealed);
            if (buffer.get() != VERSION) {
                throw new IllegalArgumentException("Unknown envelope version");
            }
            byte[] wrapped = new byte[Short.toUnsignedInt(buffer.getShort())];
            buffer.get(wrapped);
            byte[] nonce = new byte[NONCE_BYTES];
            buffer.get(nonce);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            return run(Cipher.DECRYPT_MODE, keys.unwrap(wrapped), nonce, context, ciphertext);
        } catch (java.nio.BufferUnderflowException e) {
            throw new IllegalArgumentException("Malformed envelope", e);
        }
    }

    private static byte[] run(int mode, byte[] key, byte[] nonce, byte[] context, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(context);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            // Includes AEADBadTagException: tampered value, wrong context or wrong key.
            throw new IllegalArgumentException("Cannot process the personal data envelope", e);
        }
    }
}
