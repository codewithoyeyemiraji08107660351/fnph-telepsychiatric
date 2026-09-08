package com.fnph.telepsychiatric.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "application.security.jwt")
public class JwtProperties {

    private String secretKey;
    private String issuer = "fnph-telepsychiatry";

    /** Short-lived by design. 24 hours was the previous value and is far too long. */
    private long accessTokenMinutes = 15;

    private long refreshTokenDays = 7;
    private long inactivityTimeoutMinutes = 30;
}
