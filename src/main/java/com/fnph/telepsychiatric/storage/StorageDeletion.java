package com.fnph.telepsychiatric.storage;

import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A file queued for removal.
 *
 * Unlinking inside the transaction that marks a record deleted loses the file
 * if that transaction rolls back, and a disk has no undo. The row is marked,
 * the path is queued here, and a sweeper removes it once the transaction has
 * committed and the grace period has passed.
 */
@Entity
@Table(name = "storage_deletions")
@Getter
@Setter
public class StorageDeletion extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_area", nullable = false, length = 40)
    private StorageArea storageArea;

    @Column(name = "storage_path", nullable = false, length = 500)
    private String storagePath;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "requested_by", length = 100)
    private String requestedBy;

    /** Nothing is removed before this. The window a mistake can be undone in. */
    @Column(name = "eligible_at", nullable = false)
    private LocalDateTime eligibleAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "attempts", nullable = false)
    private Integer attempts = 0;

    @Column(name = "last_error", length = 500)
    private String lastError;
}
