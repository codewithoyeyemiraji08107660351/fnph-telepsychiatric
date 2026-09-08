package com.fnph.telepsychiatric.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "application.cors")
public class CorsProperties {

    /**
     * Explicit origin allow-list. A wildcard here combined with
     * allowCredentials(true) lets any site make authenticated requests
     * against patient records, so it is never permitted.
     */
    private List<String> allowedOrigins = List.of();
}
