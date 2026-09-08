package com.fnph.telepsychiatric.supervision;

import com.fnph.telepsychiatric.authz.Role;
import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One period during which the Central Administrator viewed another user's
 * dashboard.
 *
 * Supervised access is an authorisation mode, not a role, which is why it has
 * its own record rather than a flag on a session.
 */
@Entity
@Table(name = "view_as_sessions")
@Getter
@Setter
public class ViewAsSession extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "administrator_id", nullable = false)
    private Users administrator;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_user_id", nullable = false)
    private Users targetUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_role_id", nullable = false)
    private Role targetRole;

    /** Mandatory, minimum length enforced in the API. */
    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    /**
     * A session left open is a supervised session nobody closed. Expiry means
     * an administrator who walks away does not leave the mode running.
     */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "end_reason", length = 100)
    private String endReason;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    /** How many audited actions happened inside this session. */
    @Column(name = "actions_performed", nullable = false)
    private Integer actionsPerformed = 0;

    public boolean isOpen(LocalDateTime now) {
        return endedAt == null && expiresAt.isAfter(now);
    }
}
