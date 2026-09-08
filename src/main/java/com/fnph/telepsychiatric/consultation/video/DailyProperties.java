package com.fnph.telepsychiatric.consultation.video;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "application.video.daily")
public class DailyProperties {

    private String baseUrl = "https://api.daily.co/v1";

    /**
     * The Daily API key.
     *
     * **Server-side only.** It creates rooms and mints tokens on the FNPH
     * account. In a browser it would be readable from the page source by
     * anyone, and whoever read it could create rooms, mint tokens and run up
     * charges on the hospital's account. The browser receives a meeting token
     * instead: scoped to one room, one participant, and a few minutes.
     */
    private String apiKey;

    private String domain;
    private String webhookSecret;

    private int connectTimeoutSeconds = 10;
    private int readTimeoutSeconds = 20;

    /**
     * Extra minutes the room survives past the slot end.
     *
     * Small. It covers a clock skew or a clinician finishing a sentence, not a
     * session that runs over. The room expiring at the provider is the second
     * enforcement of a fixed slot end, independent of this system being up.
     */
    private int roomGraceMinutes = 5;
}
