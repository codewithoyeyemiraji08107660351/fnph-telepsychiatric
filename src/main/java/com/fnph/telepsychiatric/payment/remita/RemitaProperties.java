package com.fnph.telepsychiatric.payment.remita;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "application.payment.remita")
public class RemitaProperties {

    private String baseUrl;
    private String merchantId;
    private String serviceTypeId;
    private String apiKey;
    private String apiToken;

    /**
     * Shared secret for callback authentication.
     *
     * Remita's callback carries no signature header by default, so the
     * callback URL includes a secret path segment and every callback is
     * re-verified server-to-server before it is believed. A callback is a
     * prompt to go and check, never evidence in itself.
     */
    private String webhookSecret;

    private String callbackUrl;
    private String responseUrl;

    private int connectTimeoutSeconds = 10;
    private int readTimeoutSeconds = 30;
}
