package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.common.ImmutableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One changed field from one Hub Coordinator edit. Insert-only.
 *
 * Kept outside the audit log on purpose: the audit log records that an edit
 * happened and why, without copying clinical text into it. This table holds
 * the before and after values the hub needs to read back.
 */
@Entity
@Table(name = "clinical_edit_revisions")
@Getter
@Setter
public class ClinicalEditRevision extends ImmutableEntity {

    public enum Target { REVIEW, FOLLOW_UP }

    @Column(name = "appointment_id", nullable = false, updatable = false)
    private Long appointmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20, updatable = false)
    private Target targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private Long targetId;

    @Column(name = "target_public_id", nullable = false, length = 26, updatable = false)
    private String targetPublicId;

    @Column(name = "field_name", nullable = false, length = 40, updatable = false)
    private String fieldName;

    @Column(name = "old_value", columnDefinition = "TEXT", updatable = false)
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT", updatable = false)
    private String newValue;

    @Column(name = "reason", nullable = false, length = 500, updatable = false)
    private String reason;

    @Column(name = "edited_by", nullable = false, length = 100, updatable = false)
    private String editedBy;

    @Column(name = "edited_by_name", length = 150, updatable = false)
    private String editedByName;

    @Column(name = "edit_group", nullable = false, length = 26, updatable = false)
    private String editGroup;
}
