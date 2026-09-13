package com.fnph.telepsychiatric.notification;

import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * Editable wording for one notification type.
 *
 * The text of a message to a patient is the kind of thing FNPH will want to
 * change after the first week of a pilot, and a redeploy to fix a sentence is a
 * redeploy that does not happen.
 *
 * A type with no row falls back to the wording in the code. An empty table must
 * not mean an empty notification.
 */
@Entity
@Table(name = "notification_templates")
@Getter
@Setter
public class NotificationTemplate extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 50)
    private NotificationType notificationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /**
     * Never true for a patient-facing message.
     *
     * A notification arrives on a phone that may be on a shared desk, so it
     * says a document is ready and not what it is for.
     */
    @Column(name = "contains_clinical_detail", nullable = false)
    private Boolean containsClinicalDetail = false;

    @Column(name = "updated_reason", length = 500)
    private String updatedReason;
}
