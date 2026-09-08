package com.fnph.telepsychiatric.authz;

import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * A role held by a user.
 *
 * An association table: composite primary key, no surrogate id and no
 * public_id. The row has no identity of its own; it IS the pair, and the pair
 * is its natural key. SchemaConventionsTest names this category explicitly so
 * it is a documented rule rather than an oversight.
 *
 * isPrimary decides the post-login destination. The database enforces at most
 * one primary role per user through a generated column and a unique index, so
 * the redirect cannot become non-deterministic through a bad write.
 */
@Entity
@Table(name = "user_role")
@EntityListeners(AuditingEntityListener.class)
@IdClass(UserRoleId.class)
@Getter
@Setter
public class UserRole {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "role_id")
    private Long roleId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private Users user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", insertable = false, updatable = false)
    private Role role;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @CreatedBy
    @Column(name = "created_by", length = 100, updatable = false)
    private String createdBy;

    /** At most one per user. Enforced by uk_user_role_single_primary. */
    @Column(name = "is_primary", nullable = false)
    private Boolean isPrimary = false;

    @Column(name = "granted_at")
    private LocalDateTime grantedAt;

    @Column(name = "granted_by", length = 100)
    private String grantedBy;

    /** Why this role was granted. Required for any non-routine assignment. */
    @Column(name = "grant_reason", length = 500)
    private String grantReason;

    /**
     * Read-only projection of the generated column that backs the
     * single-primary constraint. Mapped so Hibernate schema validation passes;
     * never written by the application.
     */
    @Column(name = "primary_marker", insertable = false, updatable = false)
    private Long primaryMarker;
}
