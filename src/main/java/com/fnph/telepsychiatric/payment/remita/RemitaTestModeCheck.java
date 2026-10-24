package com.fnph.telepsychiatric.payment.remita;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the Remita settings at start-up.
 *
 * One combination stops the application: test mode on against a live Remita
 * host. In test mode a created charge counts as paid, so against live Remita
 * every patient could book without paying. Everything else is logged, so a
 * server with a missing key still starts and serves the rest of the service.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class RemitaTestModeCheck {

    static final int MIN_WEBHOOK_SECRET_LENGTH = 24;

    private final Environment environment;
    private final RemitaProperties properties;

    @PostConstruct
    void check() {
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
        for (String problem : review(properties, prod)) {
            if (prod) {
                log.error("Remita: {}", problem);
            } else {
                log.warn("Remita: {}", problem);
            }
        }
        if (!properties.isDemoHost() && properties.hasSecretKey()) {
            log.info("Remita: live mode, host {}", hostOf(properties.getApiBaseUrl()));
        }
    }

    /**
     * Returns what is wrong with the settings, in plain words.
     *
     * @throws IllegalStateException when test mode is on against a live host
     */
    static List<String> review(RemitaProperties p, boolean prod) {
        String url = p.getApiBaseUrl();
        boolean blankUrl = url == null || url.isBlank();

        if (p.isAcceptChargeSuccessAsPaid() && !blankUrl && !p.isDemoHost()) {
            throw new IllegalStateException(
                    "REMITA_ACCEPT_CHARGE_SUCCESS_AS_PAID is true while REMITA_API_BASE_URL is a live "
                            + "Remita host. Every started payment would count as paid. Remove the "
                            + "setting or set it to false.");
        }

        List<String> problems = new ArrayList<>();
        if (blankUrl) {
            problems.add("REMITA_API_BASE_URL is not set. Payments cannot start.");
        } else {
            String path = pathOf(url);
            if (path != null && !path.isEmpty() && !path.equals("/")) {
                problems.add("REMITA_API_BASE_URL must be the host only (for example "
                        + "https://host.example). It has a path, so every Remita call will fail.");
            }
            if (!url.trim().toLowerCase().startsWith("https://")) {
                problems.add("REMITA_API_BASE_URL must start with https://.");
            }
        }
        if (!p.hasSecretKey()) {
            problems.add("REMITA_SECRET_KEY is not set. Payments cannot start until it is.");
        }
        if (p.isAcceptChargeSuccessAsPaid()) {
            problems.add("TEST MODE IS ON (REMITA_ACCEPT_CHARGE_SUCCESS_AS_PAID=true). "
                    + "A created charge counts as paid.");
        }
        if (prod && !blankUrl && p.isDemoHost()) {
            problems.add("REMITA_API_BASE_URL points at Remita's demo host. No real money moves.");
        }
        String secret = p.getWebhookSecret();
        if (secret == null || secret.length() < MIN_WEBHOOK_SECRET_LENGTH) {
            problems.add("REMITA_WEBHOOK_SECRET is missing or shorter than "
                    + MIN_WEBHOOK_SECRET_LENGTH + " characters. Generate one with "
                    + "`openssl rand -hex 24`. Remita callbacks are ignored until it is set.");
        }
        return problems;
    }

    private static String pathOf(String url) {
        try {
            return URI.create(url.trim()).getPath();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url.trim()).getHost();
        } catch (IllegalArgumentException e) {
            return "(unreadable)";
        }
    }
}
