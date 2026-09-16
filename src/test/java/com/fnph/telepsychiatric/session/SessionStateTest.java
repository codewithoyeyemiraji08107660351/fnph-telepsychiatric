package com.fnph.telepsychiatric.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules the authentication filter applies to the session behind every
 * access token. A mistake here either leaves revoked devices working or signs
 * active users out, so each rule is pinned separately.
 */
class SessionStateTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 15, 12, 0);
    private static final long IDLE_MINUTES = 30;

    private static SessionState live() {
        return new SessionState(10L, 7L, null, NOW.plusDays(3), NOW.minusMinutes(5));
    }

    @Test
    @DisplayName("a live session is usable by its own account")
    void liveSessionIsUsable() {
        assertThat(live().isUsableBy(7L, NOW, IDLE_MINUTES)).isTrue();
    }

    @Test
    @DisplayName("a session cannot be used with a token for a different account")
    void otherAccountRefused() {
        assertThat(live().isUsableBy(8L, NOW, IDLE_MINUTES)).isFalse();
        assertThat(live().isUsableBy(null, NOW, IDLE_MINUTES)).isFalse();
    }

    @Test
    @DisplayName("a revoked session is refused at once, not when the token expires")
    void revokedRefused() {
        SessionState revoked = new SessionState(10L, 7L, NOW.minusSeconds(1), NOW.plusDays(3), NOW.minusMinutes(1));
        assertThat(revoked.isUsableBy(7L, NOW, IDLE_MINUTES)).isFalse();
    }

    @Test
    @DisplayName("an expired session is refused")
    void expiredRefused() {
        SessionState expired = new SessionState(10L, 7L, null, NOW.minusSeconds(1), NOW.minusMinutes(1));
        assertThat(expired.isUsableBy(7L, NOW, IDLE_MINUTES)).isFalse();
    }

    @Test
    @DisplayName("a session idle past the timeout is refused, one inside it is not")
    void idleBoundary() {
        SessionState justInside = new SessionState(10L, 7L, null, NOW.plusDays(1), NOW.minusMinutes(IDLE_MINUTES));
        SessionState justOutside = new SessionState(10L, 7L, null, NOW.plusDays(1), NOW.minusMinutes(IDLE_MINUTES).minusSeconds(1));
        assertThat(justInside.isUsableBy(7L, NOW, IDLE_MINUTES)).isTrue();
        assertThat(justOutside.isUsableBy(7L, NOW, IDLE_MINUTES)).isFalse();
    }

    @Test
    @DisplayName("activity is written at most once a minute")
    void touchIsThrottled() {
        SessionState recent = new SessionState(10L, 7L, null, NOW.plusDays(1), NOW.minusSeconds(30));
        SessionState stale = new SessionState(10L, 7L, null, NOW.plusDays(1), NOW.minusSeconds(61));
        assertThat(recent.needsTouch(NOW)).isFalse();
        assertThat(stale.needsTouch(NOW)).isTrue();
    }
}
