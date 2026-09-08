package com.fnph.telepsychiatric.consultation.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;

/**
 * Daily, over its REST API, called from the server.
 *
 * <h2>Why the server calls it and not the browser</h2>
 *
 * The Daily API key creates rooms and mints tokens on the FNPH account. Put it
 * in a browser and it is readable from the page source by anyone who opens the
 * developer tools, and whoever reads it can create rooms, mint tokens for any
 * room including live consultations, and run up charges.
 *
 * So the server holds the key and mints a meeting token scoped to one room,
 * one participant and a few minutes. The browser runs the call with that
 * token. Same REST API, called from the side where the credential is safe.
 *
 * <h2>Rooms are private and expire at the provider</h2>
 *
 * A public room is joinable by anyone with the URL. These are created private,
 * with an expiry a few minutes past the slot end, so the provider enforces the
 * session end independently of this system being up.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DailyVideoProvider implements VideoProvider {

    private final DailyProperties properties;
    private final ObjectMapper objectMapper;

    private WebClient client() {
        return WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    @CircuitBreaker(name = "video", fallbackMethod = "roomUnavailable")
    @Retry(name = "video")
    public VideoRoom createRoom(String roomName, LocalDateTime expiresAt, boolean enableRecording) {
        long exp = expiresAt.toEpochSecond(ZoneOffset.UTC);

        Map<String, Object> properties0 = new java.util.HashMap<>(Map.of(
                // The provider deletes the room at this moment regardless of
                // what this system does. Second enforcement of the slot end.
                "exp", exp,
                "eject_at_room_exp", true,
                // Nobody enters before the clinician. A patient sitting alone
                // in a psychiatric consultation room is not a waiting room.
                "enable_knocking", false,
                "start_video_off", false,
                "start_audio_off", false,
                "enable_chat", false,
                "enable_screenshare", true,
                "max_participants", 4));

        // Off unless governance has approved it. Passed explicitly so the
        // decision is visible here rather than buried in a default.
        properties0.put("enable_recording", enableRecording ? "cloud" : false);

        Map<String, Object> body = Map.of(
                "name", roomName,
                // Private. A public room is joinable by anyone holding the URL.
                "privacy", "private",
                "properties", properties0);

        String raw = client().post().uri("/rooms")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()))
                .block();

        try {
            JsonNode node = objectMapper.readTree(raw);
            return new VideoRoom(
                    node.path("name").asText(),
                    node.path("url").asText(),
                    expiresAt,
                    node.path("id").asText(null));
        } catch (Exception e) {
            throw new VideoProviderException("Could not read the room response: " + e.getMessage());
        }
    }

    @Override
    @CircuitBreaker(name = "video", fallbackMethod = "tokenUnavailable")
    @Retry(name = "video")
    public String createParticipantToken(String roomName, String displayName, boolean owner,
                                         LocalDateTime notBefore, LocalDateTime expiresAt) {

        Map<String, Object> body = Map.of("properties", Map.of(
                "room_name", roomName,
                "user_name", displayName,
                // The clinician may admit, mute and end the call. The patient
                // may not: clinician-controlled termination is a stated
                // requirement, and a patient who could end the session could
                // end it for the doctor too.
                "is_owner", owner,
                "nbf", notBefore.toEpochSecond(ZoneOffset.UTC),
                "exp", expiresAt.toEpochSecond(ZoneOffset.UTC),
                "enable_recording_ui", false));

        String raw = client().post().uri("/meeting-tokens")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()))
                .block();

        try {
            return objectMapper.readTree(raw).path("token").asText();
        } catch (Exception e) {
            throw new VideoProviderException("Could not read the token response: " + e.getMessage());
        }
    }

    @Override
    public void deleteRoom(String roomName) {
        try {
            client().delete().uri("/rooms/{name}", roomName)
                    .retrieve().bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()))
                    .block();
            log.info("Deleted video room {}", roomName);
        } catch (Exception e) {
            // Never fails the consultation. The room also expires at the
            // provider, so this is tidiness rather than the control.
            log.warn("Could not delete video room {}: {}", roomName, e.getMessage());
        }
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature, String timestamp) {
        if (signature == null || properties.getWebhookSecret() == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    properties.getWebhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signed = (timestamp == null ? "" : timestamp + ".") + payload;
            String expected = HexFormat.of().formatHex(
                    mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));

            // Constant time. A short-circuiting compare leaks the signature one
            // character at a time through response timing.
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Webhook signature check failed: {}", e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unused")
    private VideoRoom roomUnavailable(String roomName, LocalDateTime expiresAt,
                                      boolean enableRecording, Throwable t) {
        log.error("Video provider unavailable creating room {}: {}", roomName, t.getMessage());
        throw new VideoProviderException(
                "The video service is unavailable. The consultation cannot start yet.");
    }

    @SuppressWarnings("unused")
    private String tokenUnavailable(String roomName, String displayName, boolean owner,
                                    LocalDateTime notBefore, LocalDateTime expiresAt, Throwable t) {
        log.error("Video provider unavailable minting a token for {}: {}", roomName, t.getMessage());
        throw new VideoProviderException(
                "The video service is unavailable. Try joining again in a moment.");
    }

    public static class VideoProviderException extends RuntimeException {
        public VideoProviderException(String message) {
            super(message);
        }
    }
}
