package com.fnph.telepsychiatric.security.crypto;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * Time-based one-time passwords, RFC 6238.
 *
 * Implemented directly rather than pulled in as a dependency. The algorithm is
 * forty lines of HMAC and modular arithmetic, it has not changed since 2011,
 * and a second-factor library is exactly the kind of dependency you do not want
 * to be chasing a CVE in on a clinical system.
 *
 * Compatible with Google Authenticator, Microsoft Authenticator, Authy, 1Password
 * and anything else that reads an otpauth:// URI.
 */
@Service
public class TotpService {

    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final int SECRET_BYTES = 20;
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * How many periods either side of now are accepted.
     *
     * One means a 30-second code is valid for about 90 seconds. That covers
     * clock drift on a phone and the time it takes someone to read six digits
     * and type them. Widening it further trades real security for convenience,
     * because every extra period is another valid code an attacker could land.
     */
    private static final int ALLOWED_DRIFT_PERIODS = 1;

    /** RFC 4648 base32, which is what authenticator apps expect. */
    public String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return encodeBase32(bytes);
    }

    /**
     * The otpauth:// URI the client renders as a QR code.
     *
     * The issuer appears in the app's list, so it has to identify this service
     * unambiguously: a clinician may hold factors for several systems and needs
     * to know which code is which.
     */
    public String buildProvisioningUri(String secret, String accountName, String issuer, int digits, int period) {
        String encodedIssuer = URLEncoder.encode(issuer, StandardCharsets.UTF_8);
        String encodedAccount = URLEncoder.encode(accountName, StandardCharsets.UTF_8);
        return "otpauth://totp/%s:%s?secret=%s&issuer=%s&algorithm=SHA1&digits=%d&period=%d"
                .formatted(encodedIssuer, encodedAccount, secret, encodedIssuer, digits, period);
    }

    public boolean verify(String secret, String code, int digits, int periodSeconds) {
        if (code == null || !code.matches("\\d{" + digits + "}")) {
            return false;
        }
        long counter = Instant.now().getEpochSecond() / periodSeconds;

        for (int drift = -ALLOWED_DRIFT_PERIODS; drift <= ALLOWED_DRIFT_PERIODS; drift++) {
            String expected = generateCode(secret, counter + drift, digits);
            // Constant time. A short-circuiting comparison would leak how many
            // leading digits were right through response timing.
            if (java.security.MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    code.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    String generateCode(String base32Secret, long counter, int digits) {
        try {
            byte[] key = decodeBase32(base32Secret);
            byte[] counterBytes = new byte[8];
            for (int i = 7; i >= 0; i--) {
                counterBytes[i] = (byte) (counter & 0xFF);
                counter >>>= 8;
            }

            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(counterBytes);

            // Dynamic truncation, RFC 4226 section 5.3.
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);

            int modulus = (int) Math.pow(10, digits);
            return String.format("%0" + digits + "d", binary % modulus);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP generation failed", e);
        }
    }

    private static String encodeBase32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                out.append(BASE32[(buffer >> (bitsLeft - 5)) & 0x1F]);
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            out.append(BASE32[(buffer << (5 - bitsLeft)) & 0x1F]);
        }
        return out.toString();
    }

    private static byte[] decodeBase32(String encoded) {
        String clean = encoded.replace("=", "").replace(" ", "").toUpperCase();
        byte[] out = new byte[clean.length() * 5 / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int index = 0;
        for (char c : clean.toCharArray()) {
            int value = -1;
            for (int i = 0; i < BASE32.length; i++) {
                if (BASE32[i] == c) {
                    value = i;
                    break;
                }
            }
            if (value < 0) {
                throw new IllegalArgumentException("Not valid base32: " + c);
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[index++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out;
    }
}
