package com.fnph.telepsychiatric.session;

import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * An enrolled second factor.
 *
 * The secret is encrypted rather than hashed, because the server has to
 * reproduce the code the authenticator shows and therefore needs the original
 * bytes back. See SecretEncryptor for why that matters more than it does for a
 * password.
 */
@Entity
@Table(name = "mfa_factors")
@Getter
@Setter
public class MfaFactor extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private MfaType type = MfaType.TOTP;

    @Column(name = "secret_encrypted", nullable = false, length = 512)
    private String secretEncrypted;

    @Column(name = "digits", nullable = false)
    private Integer digits = 6;

    @Column(name = "period_seconds", nullable = false)
    private Integer periodSeconds = 30;

    /**
     * Null until the user proves they can generate a code. An unverified
     * factor never satisfies a challenge, so an abandoned half-enrolment
     * cannot lock the account out.
     */
    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = false;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "failed_attempts", nullable = false)
    private Integer failedAttempts = 0;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    public boolean isUsable() {
        return Boolean.TRUE.equals(isActive)
                && verifiedAt != null
                && (lockedUntil == null || lockedUntil.isBefore(LocalDateTime.now()));
    }
}
