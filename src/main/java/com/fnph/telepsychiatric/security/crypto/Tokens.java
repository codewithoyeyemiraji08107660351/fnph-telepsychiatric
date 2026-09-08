package com.fnph.telepsychiatric.security.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Opaque secrets and the hashes stored in their place.
 *
 * Refresh tokens, invitation links, password reset links and recovery codes are
 * all long random strings compared by hash. None of them is ever stored in a
 * readable form, so a leak of the database through a backup, a log or a query
 * does not hand over working credentials.
 *
 * SHA-256 rather than BCrypt here, deliberately. BCrypt is correct for
 * passwords because a password has low entropy and must be expensive to guess.
 * These values carry 256 bits from a cryptographic source, so guessing is
 * already impossible and a deliberately slow hash would only add latency to
 * every refresh, which happens on a timer for every signed-in user.
 */
public final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private Tokens() {
    }

    /** 256 bits, URL-safe. Used for refresh tokens and link tokens. */
    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    /**
     * A recovery code a person has to read off a screen and type back.
     *
     * Groups of four from an alphabet with no I, L, O, U, 0 or 1, so it cannot
     * be misread between one and I or zero and O. Ten characters of this
     * alphabet is about 46 bits, which is far beyond guessable for a code that
     * is single use and rate limited.
     */
    public static String generateRecoveryCode() {
        final char[] alphabet = "23456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
        StringBuilder out = new StringBuilder(12);
        for (int i = 0; i < 10; i++) {
            if (i == 5) {
                out.append('-');
            }
            out.append(alphabet[RANDOM.nextInt(alphabet.length)]);
        }
        return out.toString();
    }

    /** Lowercase hex SHA-256. This is what goes in the database. */
    public static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Constant-time comparison.
     *
     * A short-circuiting equals leaks how many leading characters matched
     * through the time it takes to return, which is enough to reconstruct a
     * token one character at a time given enough attempts.
     */
    public static boolean matches(String presented, String storedHash) {
        if (presented == null || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(presented).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
