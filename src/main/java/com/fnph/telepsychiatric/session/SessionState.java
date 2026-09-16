package com.fnph.telepsychiatric.session;

import java.time.LocalDateTime;

/**
 * The handful of session columns the authentication filter needs, loaded as a
 * projection so a request never touches the lazy user association.
 *
 * Replaced rows are still usable here. Rotation marks the previous row
 * replaced, and an access token minted from it stays valid until it expires.
 * A reused refresh token revokes the whole family, which does end them.
 */
public record SessionState(
        Long id,
        Long userId,
        LocalDateTime revokedAt,
        LocalDateTime expiresAt,
        LocalDateTime lastSeenAt) {

    /** lastSeenAt is written at most once a minute per session, not on every request. */
    static final long TOUCH_INTERVAL_SECONDS = 60;

    public boolean isUsableBy(Long requestingUserId, LocalDateTime now, long inactivityMinutes) {
        return requestingUserId != null
                && requestingUserId.equals(userId)
                && revokedAt == null
                && expiresAt.isAfter(now)
                && !lastSeenAt.plusMinutes(inactivityMinutes).isBefore(now);
    }

    public boolean needsTouch(LocalDateTime now) {
        return lastSeenAt.plusSeconds(TOUCH_INTERVAL_SECONDS).isBefore(now);
    }
}
