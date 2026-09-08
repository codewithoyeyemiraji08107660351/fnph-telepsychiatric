package com.fnph.telepsychiatric.consultation.video;

import java.time.LocalDateTime;

/**
 * The video provider, behind an interface.
 *
 * The specification requires the pilot provider to sit behind a replaceable
 * interface, and the reason is practical rather than architectural taste: a
 * managed video provider is the one dependency in this system that could
 * become unavailable in Nigeria for commercial or regulatory reasons, and
 * replacing it must not mean rewriting the consultation workflow.
 *
 * Everything below is provider-neutral. Nothing outside the implementation
 * knows the word Daily.
 */
public interface VideoProvider {

    /**
     * Creates a room for one consultation.
     *
     * @param expiresAt hard expiry at the provider. A room that outlives its
     *                  slot is a room somebody can rejoin afterwards, so the
     *                  provider enforces the end as well as this system.
     * @param enableRecording ignored unless FNPH governance has approved it.
     *                        Passed explicitly rather than defaulted so the
     *                        decision is visible at the call site.
     */
    VideoRoom createRoom(String roomName, LocalDateTime expiresAt, boolean enableRecording);

    /**
     * Mints a join token for one participant.
     *
     * Short-lived, bound to the room and to a display name. The key that signs
     * it never leaves the server.
     *
     * @param owner true for the clinician, who may admit, mute and end the
     *              call. False for a patient or centre, who may not.
     */
    String createParticipantToken(String roomName, String displayName, boolean owner,
                                  LocalDateTime notBefore, LocalDateTime expiresAt);

    /**
     * Deletes the room once the session is over.
     *
     * Not housekeeping. An undeleted room with a valid token is a clinical
     * consultation anyone holding the link can walk into.
     */
    void deleteRoom(String roomName);

    /** Verifies a webhook actually came from the provider. */
    boolean verifyWebhookSignature(String payload, String signature, String timestamp);

    record VideoRoom(String name, String url, LocalDateTime expiresAt, String providerId) {
    }
}
