package com.fnph.telepsychiatric.upload;

import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.patient.CentrePatient;
import com.fnph.telepsychiatric.patient.Patient;
import com.fnph.telepsychiatric.tenancy.TenantFilters;
import com.fnph.telepsychiatric.tenancy.TenantOwned;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * fileType and status previously shared one FileStatus enum that mixed file
 * purpose with scan state. They are now FileCategory and ScanStatus.
 *
 * filePath became storageBucket plus storageKey. A local filesystem path does
 * not survive a redeploy and cannot be read by a second application node,
 * which the production topology requires.
 */
@Entity
@Table(name = "file_uploads", indexes = {
        @Index(name = "idx_file_uploads_patient", columnList = "patient_id"),
        @Index(name = "idx_file_uploads_centre", columnList = "centre_id"),
        @Index(name = "idx_file_uploads_scan", columnList = "scan_status")
})
@Getter
@Setter
@FilterDef(name = TenantFilters.CENTRE_TENANT,
           parameters = @ParamDef(name = TenantFilters.CENTRE_ID_PARAM, type = Long.class))
@Filter(name = TenantFilters.CENTRE_TENANT, condition = TenantFilters.CONDITION)
public class FileUpload extends BaseEntity implements TenantOwned {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", foreignKey = @ForeignKey(name = "fk_file_uploads_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_patient_id", foreignKey = @ForeignKey(name = "fk_file_uploads_centre_patient"))
    private CentrePatient centrePatient;

    /** Denormalised for tenant scoping. Every centre query filters on this. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id", foreignKey = @ForeignKey(name = "fk_file_uploads_centre"))
    private Center centre;

    @Column(name = "original_file_name", nullable = false, length = 255)
    private String originalFileName;

    @Column(name = "storage_bucket", nullable = false, length = 100)
    private String storageBucket;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "checksum", nullable = false, length = 64)
    private String checksum;

    @Column(name = "category", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private FileCategory category;

    @Column(name = "scan_status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private ScanStatus scanStatus = ScanStatus.UPLOADED;

    @Column(name = "uploaded_by", length = 100)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    @Column(name = "scanned_at")
    private LocalDateTime scannedAt;

    @Column(name = "scan_result", columnDefinition = "TEXT")
    private String scanResult;

    @Column(name = "quarantined_at")
    private LocalDateTime quarantinedAt;

    @Column(name = "quarantine_reason", length = 500)
    private String quarantineReason;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "reference_id", length = 50)
    private String referenceId;

    /**
     * Tenant key for isolation enforcement. Null means this row belongs to the
     * FNPH pathway rather than to a centre.
     */
    @Override
    public Long resolveCentreId() {
        return centre == null ? null : centre.getId();
    }
}
