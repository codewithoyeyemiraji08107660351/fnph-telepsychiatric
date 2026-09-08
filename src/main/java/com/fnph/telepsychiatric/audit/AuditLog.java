package com.fnph.telepsychiatric.audit;

import com.fnph.telepsychiatric.common.ImmutableEntity;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_logs_user_time", columnList = "user_id,performed_at"),
        @Index(name = "idx_audit_logs_entity", columnList = "entity_type,entity_id"),
        @Index(name = "idx_audit_logs_centre", columnList = "centre_id,performed_at")
})
@Getter
@Setter
public class AuditLog extends ImmutableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private Users user;

    @Column(name = "username", length = 50)
    private String username;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "entity_type", length = 50)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "performed_at", nullable = false)
    private LocalDateTime performedAt;

    @Column(name = "is_system", nullable = false)
    private Boolean isSystem = false;

    /**
     * The account being acted as during a Central Administrator supervised
     * session. Equals user when there is no supervision in effect.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "effective_principal_id")
    private Users effectivePrincipal;

    @Column(name = "view_as_session_id")
    private Long viewAsSessionId;

    /** Tenant scope for centre-filtered audit reporting. */
    @Column(name = "centre_id")
    private Long centreId;

    @Column(name = "outcome", nullable = false, length = 20)
    private String outcome = "SUCCESS";

    @Column(name = "before_hash", length = 64)
    private String beforeHash;

    @Column(name = "after_hash", length = 64)
    private String afterHash;

    /**
     * Covers this row's content plus the previous row's chain hash.
     *
     * Database grants already stop the application altering this table. They do
     * not stop someone holding database credentials, and "the audit trail
     * cannot be altered" has to survive that person existing. Editing or
     * removing any row breaks every hash after it, and the break is detectable
     * by anyone who can read the table.
     *
     * Tamper-evident, not tamper-proof. That is the honest guarantee and the
     * achievable one.
     */
    @Column(name = "chain_hash", length = 64)
    private String chainHash;

    @Column(name = "previous_chain_hash", length = 64)
    private String previousChainHash;

    /** Why the action was taken, where the action requires a stated reason. */
    @Column(name = "reason", length = 500)
    private String reason;
}
