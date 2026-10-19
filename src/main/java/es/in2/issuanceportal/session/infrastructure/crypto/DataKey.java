package es.in2.issuanceportal.session.infrastructure.crypto;

/**
 * A data key: the AES key itself and the same key wrapped by the key provider, which is what gets
 * stored next to every value it encrypts.
 */
public record DataKey(byte[] plaintext, byte[] wrapped) {
}
