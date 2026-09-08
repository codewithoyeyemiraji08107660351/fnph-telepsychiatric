package com.fnph.telepsychiatric.common;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Base for mutable entities.
 *
 * Every table in this schema carries the same six convention columns: id,
 * public_id, created_at, created_by, updated_at, updated_by. A convention that
 * holds everywhere can be asserted by a test; one with exceptions cannot.
 * SchemaConventionsTest enforces it against the live schema, so a future
 * migration that forgets them fails the build rather than being noticed a year
 * later during an audit.
 *
 * Soft delete is deliberately NOT here. Audit and financial ledger rows must
 * never be deletable, so they extend {@link ImmutableEntity}. Entities that
 * genuinely need soft delete extend {@link SoftDeletableEntity}.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public abstract class BaseEntity {

    /** Internal only. Never appears in a URL, an API response or a document. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The identifier every external surface uses. Nullable in the schema so the
     * column could be introduced without a backfill, but never null in practice:
     * {@link #assignPublicId()} populates it before the first insert.
     */
    @Column(name = "public_id", length = 26, updatable = false)
    private String publicId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @CreatedBy
    @Column(name = "created_by", length = 100, updatable = false)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @LastModifiedBy
    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @PrePersist
    void assignPublicId() {
        if (publicId == null) {
            publicId = PublicId.generate();
        }
    }
}
