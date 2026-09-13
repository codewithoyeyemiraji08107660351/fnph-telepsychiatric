package com.fnph.telepsychiatric.session;

import com.fnph.telepsychiatric.security.crypto.Tokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TokensTest {

    @Test
    @DisplayName("tokens do not collide across 100,000 generations")
    void tokensAreUnique() {
        Set<String> seen = new HashSet<>();
        IntStream.range(0, 100_000).forEach(i ->
                assertThat(seen.add(Tokens.generate())).as("collision at %d", i).isTrue());
    }

    @Test
    @DisplayName("tokens are URL-safe, so they survive being put in a link")
    void tokensAreUrlSafe() {
        // An invitation token that needs escaping is an invitation link that
        // breaks in some mail clients and not others.
        IntStream.range(0, 500).forEach(i ->
                assertThat(Tokens.generate()).matches("[A-Za-z0-9_-]+"));
    }

    @Test
    @DisplayName("hashing is stable and one-way")
    void hashIsStable() {
        String token = Tokens.generate();
        assertThat(Tokens.hash(token)).isEqualTo(Tokens.hash(token));
        assertThat(Tokens.hash(token)).hasSize(64).matches("[0-9a-f]+");
        assertThat(Tokens.hash(token)).isNotEqualTo(token);
    }

    @Test
    @DisplayName("matching compares the presented value against a stored hash")
    void matchesComparesAgainstHash() {
        String token = Tokens.generate();
        String stored = Tokens.hash(token);

        assertThat(Tokens.matches(token, stored)).isTrue();
        assertThat(Tokens.matches(Tokens.generate(), stored)).isFalse();
        assertThat(Tokens.matches(null, stored)).isFalse();
        assertThat(Tokens.matches(token, null)).isFalse();
    }

    @Test
    @DisplayName("recovery codes contain no character that can be misread")
    void recoveryCodesAreReadable() {
        // Someone reads these off a screen and types them back, possibly under
        // pressure after losing a phone. I, L, O, U, 0 and 1 are excluded so
        // there is nothing to confuse.
        String sample = IntStream.range(0, 2_000)
                .mapToObj(i -> Tokens.generateRecoveryCode())
                .reduce("", String::concat);

        assertThat(sample).doesNotContain("I", "L", "O", "U", "0", "1");
    }

    @Test
    @DisplayName("recovery codes carry a hyphen, which is how they are told from a TOTP code")
    void recoveryCodesAreDistinguishable() {
        assertThat(Tokens.generateRecoveryCode()).matches("[A-Z2-9]{5}-[A-Z2-9]{5}");
    }
}
