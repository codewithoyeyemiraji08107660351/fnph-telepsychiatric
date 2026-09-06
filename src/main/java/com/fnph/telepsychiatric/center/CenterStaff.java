package com.fnph.telepsychiatric.center;

import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.common.Roles;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "centre_staff")
@Getter
@Setter
public class CenterStaff extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id", nullable = false)
    private Center centre;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    @Column(name = "role", nullable = false)
    @Enumerated(EnumType.STRING)
    private Roles role;

    @Column(name = "is_primary", nullable = false)
    private Boolean isPrimary = false;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "activated_by")
    private String activatedBy;
}
