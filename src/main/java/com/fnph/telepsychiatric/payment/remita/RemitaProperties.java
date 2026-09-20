package com.fnph.telepsychiatric.payment.remita;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "application.payment.remita")
public class RemitaProperties {

    /**
     * Connect Gateway base URL.
     *
     * TEST:
     * https://api-demo.systemspecsng.com
     *
     * LIVE:
     * supplied by Remita when live credentials are issued.
     */
    private String apiBaseUrl;

    /**
     * Connect Gateway secret key.
     */
    private String secretKey;

    /**
     * Frontend application URL.
     *
     * Example:
     * http://localhost:5173
     *
     * Production:
     * https://app.fnphkaduna.cloud
     */
    private String frontendUrl;

    /**
     * TEST ONLY:
     *
     * When true, a successful Connect Gateway CHARGE response
     * (status 00) is treated as payment success.
     *
     * MUST be false for production/live Remita.
     */
    private boolean acceptChargeSuccessAsPaid = true;

    private int readTimeoutSeconds = 30;
    private int connectTimeoutSeconds = 10;
}