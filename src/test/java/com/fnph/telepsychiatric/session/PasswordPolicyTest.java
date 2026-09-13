package com.fnph.telepsychiatric.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

    private PasswordPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new PasswordPolicy(new AuthProperties());
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "elevenchar", "Passw0rd!"})
    @DisplayName("rejects anything under twelve characters, however complicated")
    void rejectsShortPasswords(String candidate) {
        assertThat(policy.validate(candidate, "dr.bello", "a@b.test", "Aisha Bello"))
                .isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"password123", "qwertyuiop", "administrator", "telepsychiatry"})
    @DisplayName("rejects the passwords any attack list tries first")
    void rejectsBlocklisted(String candidate) {
        assertThat(policy.validate(candidate, "dr.bello", "a@b.test", "Aisha Bello"))
                .isNotEmpty();
    }

    @Test
    @DisplayName("rejects a password containing the username or email")
    void rejectsPersonalDetails() {
        assertThat(policy.validate("drbelloSomething1", "drbello", "a@b.test", "Aisha Bello"))
                .isNotEmpty();
        assertThat(policy.validate("abellowinter2026", "x.y", "abello@fnph.test", "Aisha Bello"))
                .isNotEmpty();
    }

    @Test
    @DisplayName("rejects a password containing the account holder's name")
    void rejectsOwnName() {
        // Guessable by anyone who can see a staff directory.
        assertThat(policy.validate("aishaisgreat2026", "x.y", "z@b.test", "Aisha Bello"))
                .isNotEmpty();
    }

    @Test
    @DisplayName("rejects a long password made of almost no distinct characters")
    void rejectsLowVariety() {
        assertThat(policy.validate("aaaaaaaaaaaaaaaa", "x.y", "z@b.test", "Aisha Bello"))
                .isNotEmpty();
    }

    @Test
    @DisplayName("accepts a long passphrase with no special characters at all")
    void acceptsPassphrase() {
        // The point of the policy. Length beats composition: forcing an
        // uppercase, a digit and a symbol reliably produces Password1! and
        // nothing better.
        assertThat(policy.validate("correct horse battery staple", "dr.bello",
                "a.bello@fnph.test", "Aisha Bello"))
                .isEmpty();
    }

    @Test
    @DisplayName("reports every problem at once rather than one at a time")
    void reportsAllProblems() {
        // Returning them one by one makes the user guess repeatedly.
        assertThat(policy.validate("aaaa", "dr.bello", "a@b.test", "Aisha Bello"))
                .hasSizeGreaterThan(1);
    }
}
