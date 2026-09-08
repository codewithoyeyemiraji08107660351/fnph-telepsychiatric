package com.fnph.telepsychiatric.security.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM for values that must be readable again, which for now means TOTP
 * shared secrets.
 *
 * A TOTP secret cannot be hashed: the server has to reproduce the code the
 * authenticator app shows, so it needs the original bytes. Storing it in clear
 * would mean a read-only leak of mfa_factors lets an attacker generate valid
 * second factors indefinitely, and unlike a password leak nobody would know to
 * rotate anything.
 *
 * GCM rather than CBC because it authenticates as well as encrypts: a modified
 * ciphertext fails to decrypt rather than producing plausible garbage. The
 * nonce is random per encryption and stored in front of the ciphertext, which
 * is safe because it is never reused with the same key.
 */
@Component
public class SecretEncryptor {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretEncryptor(@Value("${application.security.encryption.key}") String base64Key) {
        byte[] decoded = Base64.getDecoder().decode(base64Key);
        if (decoded.length != 32) {
            throw new IllegalStateException(
                    "application.security.encryption.key must decode to exactly 32 bytes for AES-256, got "
                            + decoded.length + ". Generate one with: openssl rand -base64 32");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] combined = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(ciphertext, 0, combined, nonce.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            byte[] nonce = new byte[NONCE_BYTES];
            byte[] ciphertext = new byte[combined.length - NONCE_BYTES];
            System.arraycopy(combined, 0, nonce, 0, NONCE_BYTES);
            System.arraycopy(combined, NONCE_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed. Has the encryption key changed?", e);
        }
    }
}
