package com.fnph.telepsychiatric.common;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * For entities that may be deactivated without destroying history.
 *
 * Removing a user must never remove their clinical, financial or audit trail.
 * Deactivation records who did it, when and why.
 */
@MappedSuperclass
@Getter
@Setter
public abstract class SoftDeletableEntity extends BaseEntity {

    @Column(name = "deleted", nullable = false)
    private Boolean deleted = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "deleted_by", length = 100)
    private String deletedBy;

    @Column(name = "deleted_reason", length = 500)
    private String deletedReason;
}
