package com.fnph.telepsychiatric.session;

import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A single-use, time-limited link token: invitation activation or password
 * reset.
 *
 * One entity for both because the rules are identical. Issuing a new token for
 * the same purpose invalidates the outstanding one, so a forwarded or
 * intercepted old email stops working the moment a fresh one is requested.
 */
@Entity
@Table(name = "account_tokens")
@Getter
@Setter
public class AccountToken extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 30)
    private AccountTokenPurpose purpose;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "used_ip", length = 45)
    private String usedIp;

    @Column(name = "issued_by", length = 100)
    private String issuedBy;

    @Column(name = "issued_ip", length = 45)
    private String issuedIp;

    @Column(name = "invalidated_at")
    private LocalDateTime invalidatedAt;

    public boolean isUsable(LocalDateTime now) {
        return usedAt == null && invalidatedAt == null && expiresAt.isAfter(now);
    }
}
