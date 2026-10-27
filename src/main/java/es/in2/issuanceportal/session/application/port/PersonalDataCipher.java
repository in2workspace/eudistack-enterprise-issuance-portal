package es.in2.issuanceportal.session.application.port;

/**
 * Encrypts the personal data of the journey session. The context is bound to the ciphertext as
 * associated data: decrypting with a different context fails, so a value cannot be moved to another
 * journey or another column.
 */
public interface PersonalDataCipher {

    byte[] encrypt(byte[] plaintext, byte[] context);

    byte[] decrypt(byte[] sealed, byte[] context);
}
