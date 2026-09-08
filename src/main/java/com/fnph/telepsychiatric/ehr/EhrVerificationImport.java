package com.fnph.telepsychiatric.ehr;

import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * One uploaded snapshot of the offline FNPH EHR.
 *
 * Versioned rather than overwritten, so a bad file can be rejected without
 * destroying the snapshot enrolment is currently matching against, and so two
 * snapshots can be compared to find accounts whose details have changed.
 */
@Entity
@Table(name = "ehr_verification_imports")
@Getter
@Setter
public class EhrVerificationImport extends BaseEntity {

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    /** Rejects the same file being uploaded twice by mistake. */
    @Column(name = "file_checksum", nullable = false, length = 64)
    private String fileChecksum;

    @Column(name = "file_size_bytes", nullable = false)
    private Long fileSizeBytes;

    /**
     * When the hospital extracted the file, supplied by the uploader.
     *
     * Not the upload date. A file extracted in June and uploaded in September
     * is three months stale, and every screen reading this snapshot has to say
     * so rather than implying it is current.
     */
    @Column(name = "source_as_at", nullable = false)
    private LocalDate sourceAsAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ImportStatus status = ImportStatus.UPLOADED;

    @Column(name = "row_count", nullable = false)
    private Integer rowCount = 0;

    @Column(name = "valid_row_count", nullable = false)
    private Integer validRowCount = 0;

    @Column(name = "rejected_row_count", nullable = false)
    private Integer rejectedRowCount = 0;

    /** Line-numbered errors. A rejection without them is unusable. */
    @Column(name = "validation_report", columnDefinition = "LONGTEXT")
    private String validationReport;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by_id")
    private Users uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "activated_by", length = 100)
    private String activatedBy;

    @Column(name = "superseded_at")
    private LocalDateTime supersededAt;

    @Column(name = "superseded_by_import_id")
    private Long supersededByImportId;

    /** Active accounts whose stored name or phone differs from this snapshot. */
    @Column(name = "drift_detected_count", nullable = false)
    private Integer driftDetectedCount = 0;

    /** How stale this snapshot is, in days. Shown wherever it is relied on. */
    public long ageInDays() {
        return ChronoUnit.DAYS.between(sourceAsAt, LocalDate.now());
    }
}
