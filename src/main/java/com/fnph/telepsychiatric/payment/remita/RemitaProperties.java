package com.fnph.telepsychiatric.payment.remita;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.Locale;

/**
 * Remita Connect Gateway settings.
 *
 * Going live changes two values only: REMITA_API_BASE_URL (the live host
 * Remita issues with the live credentials) and REMITA_SECRET_KEY. Everything
 * else is the same in test and live.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "application.payment.remita")
public class RemitaProperties {

    /**
     * Connect Gateway host, no path.
     *
     * Test: https://api-demo.systemspecsng.com
     * Live: the host in Remita's live credentials email.
     */
    private String apiBaseUrl;

    /** Connect Gateway secret key. Sent as the secretKey header. */
    private String secretKey;

    /**
     * Path segment in the callback URL Remita is given:
     * {api}/api/v1/webhooks/remita/{webhookSecret}.
     *
     * Remita signs nothing, so this keeps the endpoint from being found and
     * used to make the server call Remita on demand. It is not what decides a
     * payment; the server-to-server check is.
     */
    private String webhookSecret;

    /** Frontend application URL, for example https://app.fnphkaduna.cloud. */
    private String frontendUrl;

    /**
     * TEST ONLY. True counts Remita's "charge created" reply (status 00) as
     * paid. The application refuses to start with this on against a live host.
     */
    private boolean acceptChargeSuccessAsPaid = false;

    private int readTimeoutSeconds = 30;
    private int connectTimeoutSeconds = 10;

    /** True when the base URL points at one of Remita's demo hosts. */
    public boolean isDemoHost() {
        return isDemoHost(apiBaseUrl);
    }

    public static boolean isDemoHost(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String host;
        try {
            host = URI.create(url.trim()).getHost();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        return host.contains("demo") || host.contains("sandbox") || host.contains("test")
                || host.equals("localhost") || host.startsWith("127.");
    }

    public boolean hasSecretKey() {
        return secretKey != null && !secretKey.isBlank();
    }
}
