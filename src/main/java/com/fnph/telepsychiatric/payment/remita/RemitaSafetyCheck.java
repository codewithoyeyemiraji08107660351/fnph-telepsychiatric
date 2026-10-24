package com.fnph.telepsychiatric.payment.remita;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;

/**
 * Refuses to start in a configuration that would take patients' bookings
 * without their money.
 *
 * Test mode (accept-charge-success-as-paid) marks a payment paid the moment
 * Remita accepts the charge request, before the patient pays anything. That is
 * only tolerable against a Remita demo host. Against a live host it gives the
 * service away, so the application stops at start-up rather than run that way.
 *
 * Also reports, without printing any value, which settings are missing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RemitaSafetyCheck {

    private final RemitaProperties properties;

    @PostConstruct
    void check() {
        boolean demo = isDemoHost(properties.getApiBaseUrl());

        if (properties.isAcceptChargeSuccessAsPaid()) {
            if (!demo) {
                throw new IllegalStateException(
                        "REMITA_ACCEPT_CHARGE_SUCCESS_AS_PAID is true but REMITA_API_BASE_URL is not a "
                                + "Remita demo host. Test mode marks payments paid before any money moves. "
                                + "Set REMITA_ACCEPT_CHARGE_SUCCESS_AS_PAID=false for live keys.");
            }
            log.warn("Remita TEST MODE is on: a charge accepted by the demo gateway is treated as paid. "
                    + "Never use this setting with live keys.");
        }

        if (isBlank(properties.getApiBaseUrl())) {
            log.error("REMITA_API_BASE_URL is not set. Patients cannot pay.");
        }
        if (isBlank(properties.getSecretKey())) {
            log.error("REMITA_SECRET_KEY is not set. Patients cannot pay.");
        }
        if (isBlank(properties.getWebhookSecret())) {
            log.error("REMITA_WEBHOOK_SECRET is not set. Every Remita notification will be ignored; "
                    + "payments are then confirmed only by the patient's page and the pending sweep.");
        }
        log.info("Remita configured: host={}, mode={}", hostOf(properties.getApiBaseUrl()),
                demo ? "DEMO" : "LIVE");
    }

    /** Demo hosts carry "demo" in the host name: api-demo.systemspecsng.com, demo.remita.net, remitademo.net. */
    static boolean isDemoHost(String baseUrl) {
        String host = hostOf(baseUrl);
        return host != null && host.toLowerCase(Locale.ROOT).contains("demo");
    }

    private static String hostOf(String baseUrl) {
        if (isBlank(baseUrl)) {
            return null;
        }
        try {
            return URI.create(baseUrl.trim()).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
