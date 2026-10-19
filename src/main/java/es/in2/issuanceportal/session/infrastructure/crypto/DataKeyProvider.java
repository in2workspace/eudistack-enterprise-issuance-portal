package es.in2.issuanceportal.session.infrastructure.crypto;

/** Hands out the data key to encrypt with, and recovers the key of a value encrypted earlier. */
public interface DataKeyProvider {

    DataKey currentKey();

    /** The plaintext AES key of a wrapped data key, written by this or any other instance. */
    byte[] unwrap(byte[] wrapped);
}
