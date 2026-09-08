package com.fnph.telepsychiatric.notification;

import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.authz.Role;
import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.patient.Patient;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Previously all three of type, channel and status used one NotificationStatus
 * enum holding twenty-one unrelated values, which made the data unqueryable.
 */
@Entity
@Table(name = "notifications", indexes = {
        @Index(name = "idx_notifications_user_status", columnList = "user_id,status"),
        @Index(name = "idx_notifications_scheduled", columnList = "scheduled_for,status")
})
@Getter
@Setter
public class Notification extends BaseEntity {

    /** Set for a person-addressed notification. Null for a dashboard one. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", foreignKey = @ForeignKey(name = "fk_notifications_user"))
    private Users user;

    /**
     * Set for a dashboard-addressed notification.
     *
     * A booking arriving for approval belongs to whoever is on the desk, not to
     * a named coordinator. Any holder of the role can acknowledge it, and the
     * unread count is then the amount of work waiting rather than a per-person
     * copy of it.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_role_id")
    private Role targetRole;

    /** Restricts a role-addressed item to one centre's holders of that role. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id")
    private Center centre;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @Column(name = "type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private NotificationType type;

    @Column(name = "channel", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private NotificationChannel channel;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private DeliveryStatus status = DeliveryStatus.PENDING;

    @Column(name = "template_code", length = 100)
    private String templateCode;

    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    /**
     * Push and SMS payloads must never expose a diagnosis, a medication name or
     * any other clinical detail on a locked screen. When this is true the
     * delivery layer sends a neutral placeholder and requires an authenticated
     * session before the content is shown.
     */
    @Column(name = "contains_clinical_detail", nullable = false)
    private Boolean containsClinicalDetail = false;

    @Column(name = "scheduled_for")
    private LocalDateTime scheduledFor;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    private Integer maxRetries = 3;

    @Column(name = "reference_id", length = 50)
    private String referenceId;

    /** Where clicking it takes the user. */
    @Column(name = "action_url", length = 300)
    private String actionUrl;

    @Column(name = "entity_type", length = 50)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "dismissed_at")
    private LocalDateTime dismissedAt;

    /** Who took a dashboard item off everyone else's list. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "acknowledged_by_id")
    private Users acknowledgedBy;

    @Column(name = "priority", nullable = false)
    private Integer priority = 0;
}
