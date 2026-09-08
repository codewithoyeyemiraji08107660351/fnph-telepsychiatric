package com.fnph.telepsychiatric.session;

import com.fnph.telepsychiatric.common.ImmutableEntity;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Every sign-in attempt, successful or not. Append-only.
 *
 * usernameAttempted holds what was typed rather than a resolved account,
 * because attempts against usernames that do not exist are the interesting
 * ones: a run of them from one address is an enumeration attempt.
 */
@Entity
@Table(name = "login_attempts")
@Getter
@Setter
public class LoginAttempt extends ImmutableEntity {

    @Column(name = "username_attempted", nullable = false, length = 150)
    private String usernameAttempted;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private Users user;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 30)
    private LoginOutcome outcome;

    @Column(name = "failure_reason", length = 200)
    private String failureReason;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;
}
