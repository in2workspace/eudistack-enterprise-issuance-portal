package es.in2.issuanceportal.session.infrastructure.crypto;

import java.util.Arrays;

/**
 * A data key: the AES key itself and the same key wrapped by the key provider, which is what gets
 * stored next to every value it encrypts. Equality compares contents, and the key never prints.
 */
public record DataKey(byte[] plaintext, byte[] wrapped) {

    @Override
    public boolean equals(Object other) {
        return other instanceof DataKey(byte[] otherPlaintext, byte[] otherWrapped)
                && Arrays.equals(plaintext, otherPlaintext)
                && Arrays.equals(wrapped, otherWrapped);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(plaintext) + Arrays.hashCode(wrapped);
    }

    @Override
    public String toString() {
        return "DataKey[redacted]";
    }
}
