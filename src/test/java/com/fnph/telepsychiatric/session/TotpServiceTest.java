package com.fnph.telepsychiatric.session;

import com.fnph.telepsychiatric.security.crypto.TotpService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RFC 6238 Appendix B test vectors.
 *
 * These matter more than they look. A TOTP implementation that is subtly wrong
 * still produces six digits that verify against itself, so it passes every
 * naive test and then fails against the authenticator app on a clinician's
 * phone. The published vectors are the only thing that proves interoperability.
 */
class TotpServiceTest {

    private final TotpService service = new TotpService();

    /** The RFC's seed, "12345678901234567890", base32 encoded. */
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @ParameterizedTest(name = "T={0} produces {1}")
    @CsvSource({
            "59,          94287082",
            "1111111109,  07081804",
            "1111111111,  14050471",
            "1234567890,  89005924",
            "2000000000,  69279037",
            "20000000000, 65353130"
    })
    @DisplayName("matches the RFC 6238 published vectors")
    void matchesRfcVectors(long epochSecond, String expected) {
        assertThat(service.generateCode(RFC_SECRET, epochSecond / 30, 8)).isEqualTo(expected);
    }

    @Test
    @DisplayName("accepts a code from the previous and next window")
    void acceptsAdjacentWindows() {
        // Covers clock drift on a phone and the seconds it takes to read six
        // digits and type them.
        String secret = service.generateSecret();
        long now = Instant.now().getEpochSecond() / 30;

        assertThat(service.verify(secret, service.generateCode(secret, now, 6), 6, 30)).isTrue();
        assertThat(service.verify(secret, service.generateCode(secret, now - 1, 6), 6, 30)).isTrue();
        assertThat(service.verify(secret, service.generateCode(secret, now + 1, 6), 6, 30)).isTrue();
    }

    @Test
    @DisplayName("rejects a code from further out than the drift window")
    void rejectsStaleCodes() {
        // Every extra accepted window is another valid code an attacker could
        // land, so the window stays narrow.
        String secret = service.generateSecret();
        long now = Instant.now().getEpochSecond() / 30;

        assertThat(service.verify(secret, service.generateCode(secret, now - 3, 6), 6, 30)).isFalse();
        assertThat(service.verify(secret, service.generateCode(secret, now + 3, 6), 6, 30)).isFalse();
    }

    @Test
    @DisplayName("rejects malformed input without throwing")
    void rejectsMalformed() {
        String secret = service.generateSecret();
        assertThat(service.verify(secret, null, 6, 30)).isFalse();
        assertThat(service.verify(secret, "", 6, 30)).isFalse();
        assertThat(service.verify(secret, "12345", 6, 30)).isFalse();
        assertThat(service.verify(secret, "1234567", 6, 30)).isFalse();
        assertThat(service.verify(secret, "ABCDEF", 6, 30)).isFalse();
    }

    @Test
    @DisplayName("generates a secret every authenticator app can read")
    void generatesValidSecret() {
        String secret = service.generateSecret();
        // 20 bytes base32 encodes to 32 characters, RFC 4648 alphabet.
        assertThat(secret).hasSize(32).matches("[A-Z2-7]+");
    }

    @Test
    @DisplayName("builds an otpauth URI with the issuer visible in the app")
    void buildsProvisioningUri() {
        // A clinician may hold factors for several systems and needs to know
        // which code belongs to which.
        String uri = service.buildProvisioningUri(
                "GEZDGNBVGY3TQOJQ", "a.bello@fnphkaduna.gov.ng",
                "FNPH Kaduna Telepsychiatry", 6, 30);

        assertThat(uri).startsWith("otpauth://totp/");
        assertThat(uri).contains("secret=GEZDGNBVGY3TQOJQ");
        assertThat(uri).contains("issuer=FNPH");
        assertThat(uri).contains("digits=6").contains("period=30");
    }

    @Test
    @DisplayName("two secrets are never the same")
    void secretsAreUnique() {
        assertThat(service.generateSecret()).isNotEqualTo(service.generateSecret());
    }
}
