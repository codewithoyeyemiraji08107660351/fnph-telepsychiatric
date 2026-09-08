package com.fnph.telepsychiatric.session;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Password rules.
 *
 * Length is the requirement, not composition. Forcing an uppercase, a digit and
 * a symbol reliably produces Password1! and nothing better, because people
 * satisfy the rule in the cheapest way available. Twelve characters with a
 * blocklist of the obvious choices gets more real entropy and fewer written-
 * down passwords, which matters on a shared clinical workstation.
 *
 * This follows the NIST 800-63B guidance: length, a blocklist, and no forced
 * periodic rotation.
 */
@Component
@RequiredArgsConstructor
public class PasswordPolicy {

    private final AuthProperties properties;

    /** Passwords that any list of the common ones would try first. */
    private static final Set<String> BLOCKED = Set.of(
            "password", "password1", "password123", "passw0rd",
            "qwerty", "qwertyuiop", "123456", "12345678", "123456789",
            "letmein", "welcome", "admin", "administrator",
            "fnph", "fnphkaduna", "telepsychiatry", "kaduna",
            "changeme", "iloveyou", "abc123", "monkey", "dragon");

    /**
     * @return the reasons this password is unacceptable, empty when it is fine.
     *         All reasons at once, because returning them one at a time makes
     *         the user guess repeatedly.
     */
    public List<String> validate(String password, String username, String email, String fullName) {
        List<String> problems = new ArrayList<>();

        if (password == null || password.isBlank()) {
            problems.add("Enter a password");
            return problems;
        }
        if (password.length() < properties.getMinPasswordLength()) {
            problems.add("Use at least " + properties.getMinPasswordLength()
                    + " characters. A short phrase you can remember is fine and is better "
                    + "than a short complicated one.");
        }
        if (password.length() > 200) {
            problems.add("Keep it under 200 characters");
        }

        String lower = password.toLowerCase();
        if (BLOCKED.contains(lower)) {
            problems.add("That password is on every list an attacker would try first");
        }
        for (String blocked : BLOCKED) {
            if (lower.contains(blocked) && lower.length() < blocked.length() + 4) {
                problems.add("That is too close to a very common password");
                break;
            }
        }

        // A password containing the account's own details is guessable by
        // anyone who can see a staff directory.
        if (containsPersonalDetail(lower, username) || containsPersonalDetail(lower, email)) {
            problems.add("Do not use your username or email address in your password");
        }
        if (fullName != null) {
            for (String part : fullName.toLowerCase().split("\\s+")) {
                if (part.length() >= 4 && lower.contains(part)) {
                    problems.add("Do not use your name in your password");
                    break;
                }
            }
        }

        if (password.chars().distinct().count() < 5) {
            problems.add("Use a wider variety of characters");
        }

        return problems;
    }

    private boolean containsPersonalDetail(String lowerPassword, String detail) {
        if (detail == null || detail.isBlank()) {
            return false;
        }
        String candidate = detail.toLowerCase().split("@")[0];
        return candidate.length() >= 4 && lowerPassword.contains(candidate);
    }
}
