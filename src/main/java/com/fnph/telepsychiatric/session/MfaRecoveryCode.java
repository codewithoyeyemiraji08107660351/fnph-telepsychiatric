package com.fnph.telepsychiatric.session;

import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Single-use code for a lost authenticator. Stored hashed. */
@Entity
@Table(name = "mfa_recovery_codes")
@Getter
@Setter
public class MfaRecoveryCode extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "used_ip", length = 45)
    private String usedIp;
}
