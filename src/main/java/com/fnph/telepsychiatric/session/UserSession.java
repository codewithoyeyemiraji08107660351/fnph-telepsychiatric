package com.fnph.telepsychiatric.session;

import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One signed-in device.
 *
 * Replaces the single refresh token column on the user row, which meant one
 * session per account and no way to revoke a lost phone without signing the
 * user out everywhere.
 */
@Entity
@Table(name = "user_sessions")
@Getter
@Setter
public class UserSession extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    /**
     * Shared by every token descended from one sign-in. Rotation creates a new
     * row in the same family; revoking the family ends the whole chain.
     */
    @Column(name = "family_id", nullable = false, length = 26)
    private String familyId;

    /** SHA-256 of the refresh token. The token itself is never stored. */
    @Column(name = "refresh_token_hash", nullable = false, length = 64)
    private String refreshTokenHash;

    @Column(name = "device_label", length = 120)
    private String deviceLabel;

    @Column(name = "device_fingerprint", length = 64)
    private String deviceFingerprint;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    /**
     * Advanced on every authenticated request. Inactivity is measured from
     * here rather than from issuedAt, so an active session is not cut off at a
     * fixed interval while an abandoned one still expires.
     */
    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoked_by", length = 100)
    private String revokedBy;

    @Column(name = "revoked_reason", length = 200)
    private String revokedReason;

    @Column(name = "replaced_at")
    private LocalDateTime replacedAt;

    @Column(name = "replaced_by_session_id")
    private Long replacedBySessionId;

    /**
     * The raw refresh token, present only on the instance returned from an
     * issue or rotate call.
     *
     * Marked @Transient: it is never written to the database, which is the
     * whole point of storing only the hash. It exists so the caller can put the
     * token in the response without a second lookup that could not recover it
     * anyway.
     */
    @Transient
    private String transientRawToken;

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isReplaced() {
        return replacedAt != null;
    }

    public boolean isExpired(LocalDateTime now) {
        return expiresAt.isBefore(now);
    }

    public boolean isIdleBeyond(LocalDateTime now, long inactivityMinutes) {
        return lastSeenAt.plusMinutes(inactivityMinutes).isBefore(now);
    }

    public boolean isUsable(LocalDateTime now, long inactivityMinutes) {
        return !isRevoked() && !isReplaced() && !isExpired(now)
                && !isIdleBeyond(now, inactivityMinutes);
    }
}
