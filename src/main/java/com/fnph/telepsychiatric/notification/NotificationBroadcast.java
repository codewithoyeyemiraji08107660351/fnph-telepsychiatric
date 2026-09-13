package com.fnph.telepsychiatric.notification;

import com.fnph.telepsychiatric.authz.Role;
import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A sent announcement.
 *
 * Recorded rather than logged, because "everyone was told about the outage on
 * Tuesday" is a claim somebody will need to check, and the count of who
 * actually received it is part of the answer.
 */
@Entity
@Table(name = "notification_broadcasts")
@Getter
@Setter
public class NotificationBroadcast extends BaseEntity {

    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    /** Null with a null centre means everyone. Recorded plainly for that reason. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_role_id")
    private Role targetRole;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id")
    private Center centre;

    @Column(name = "sent_by", nullable = false, length = 100)
    private String sentBy;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @Column(name = "recipient_count", nullable = false)
    private Integer recipientCount = 0;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;
}
