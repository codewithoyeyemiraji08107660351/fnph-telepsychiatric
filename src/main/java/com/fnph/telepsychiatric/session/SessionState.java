package com.fnph.telepsychiatric.session;

import java.time.LocalDateTime;

public record SessionState(
        Long id,
        Long userId,
        LocalDateTime revokedAt,
        LocalDateTime expiresAt,
        LocalDateTime lastSeenAt) {

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
