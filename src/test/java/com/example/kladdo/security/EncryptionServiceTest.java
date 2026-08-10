package com.example.kladdo.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the AES-GCM encryption of stored secrets. Pure logic, no Spring context / no H2 - fast
 * and free of the file-lock caveat that afflicts JPA-context tests.
 */
class EncryptionServiceTest {

    private final EncryptionService service = new EncryptionService("test-encryption-key-any-length-works");

    @Test
    void roundTripsPlaintext() {
        String secret = "smtp-p@ssw0rd with spaces & symbols $\\";
        assertEquals(secret, service.decrypt(service.encrypt(secret)));
    }

    @Test
    void samePlaintextEncryptsDifferentlyEachTime() {
        // A fresh random IV per call means identical plaintext must not produce identical ciphertext.
        String secret = "hunter2";
        assertNotEquals(service.encrypt(secret), service.encrypt(secret));
    }

    @Test
    void tamperedCiphertextFailsToDecrypt() {
        String encoded = service.encrypt("hunter2");
        // Flip a character in the Base64 payload; GCM's auth tag must reject it.
        char flipped = encoded.charAt(encoded.length() - 2) == 'A' ? 'B' : 'A';
        String tampered = encoded.substring(0, encoded.length() - 2) + flipped + encoded.charAt(encoded.length() - 1);
        assertThrows(IllegalStateException.class, () -> service.decrypt(tampered));
    }

    @Test
    void differentKeysCannotDecryptEachOther() {
        String encoded = service.encrypt("hunter2");
        EncryptionService other = new EncryptionService("a-totally-different-key");
        assertThrows(IllegalStateException.class, () -> other.decrypt(encoded));
    }
}
