package com.fnph.telepsychiatric.common;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Base for append-only records: audit events and the centre wallet ledger.
 *
 * No updated_at, no updated_by and no soft delete, because these rows are never
 * modified. UPDATE and DELETE are additionally revoked on these tables at the
 * database grant level, so the guarantee does not depend on the application
 * behaving correctly. See db/ops/append_only_grants.sql.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public abstract class ImmutableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", length = 26, updatable = false)
    private String publicId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @CreatedBy
    @Column(name = "created_by", length = 100, updatable = false)
    private String createdBy;

    @PrePersist
    void assignPublicId() {
        if (publicId == null) {
            publicId = PublicId.generate();
        }
    }
}
