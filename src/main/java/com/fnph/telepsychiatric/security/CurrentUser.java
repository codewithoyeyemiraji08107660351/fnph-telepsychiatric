package com.fnph.telepsychiatric.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Access to the authenticated principal without threading it through every
 * method signature.
 *
 * Deliberately returns Optional. Scheduled jobs, webhook handlers and the
 * public document verification endpoint all run with no principal, and code
 * that assumes one is always present fails at the worst moment.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<SecurityUser> get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        return authentication.getPrincipal() instanceof SecurityUser user
                ? Optional.of(user)
                : Optional.empty();
    }

    public static SecurityUser require() {
        return get().orElseThrow(() ->
                new IllegalStateException("No authenticated principal in this context"));
    }

    /** The centre every query in this request must be constrained to. */
    public static Optional<Long> centreId() {
        return get().map(SecurityUser::getCentreId);
    }

    public static Optional<Long> patientId() {
        return get().map(SecurityUser::getPatientId);
    }

    public static String usernameOrSystem() {
        return get().map(SecurityUser::getUsername).orElse("system");
    }
}
