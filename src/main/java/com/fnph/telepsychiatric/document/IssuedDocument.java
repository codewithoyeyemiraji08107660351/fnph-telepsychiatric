package com.fnph.telepsychiatric.document;

import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.clinical.ReleaseBundle;
import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.patient.CentrePatient;
import com.fnph.telepsychiatric.patient.Patient;
import com.fnph.telepsychiatric.tenancy.TenantFilters;
import com.fnph.telepsychiatric.tenancy.TenantOwned;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.time.LocalDateTime;

/**
 * A document released to a patient or a centre.
 *
 * One table for every type. Download counters, validity and QR verification
 * were previously duplicated on prescriptions and investigations, and two
 * copies of the same rules drift: the visible symptom is a prescription that
 * expires correctly while an investigation request quietly does not.
 */
@Entity
@Table(name = "issued_documents")
@FilterDef(name = TenantFilters.CENTRE_TENANT,
           parameters = @ParamDef(name = TenantFilters.CENTRE_ID_PARAM, type = Long.class))
@Filter(name = TenantFilters.CENTRE_TENANT, condition = TenantFilters.CONDITION)
@Getter
@Setter
public class IssuedDocument extends BaseEntity implements TenantOwned {

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 30)
    private DocumentType documentType;

    /** The prescription, investigation or note this was rendered from. */
    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    /** Quoted on the phone. Formatted to be unambiguous when read aloud. */
    @Column(name = "issue_number", nullable = false, length = 50)
    private String issueNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_patient_id")
    private CentrePatient centrePatient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id")
    private Center centre;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bundle_id")
    private ReleaseBundle bundle;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DocumentStatus status = DocumentStatus.ACTIVE;

    /**
     * Frozen at issue from configuration.
     *
     * A patient's allowance must not change under them because governance
     * adjusted a setting after their prescription was issued.
     */
    @Column(name = "max_downloads", nullable = false)
    private Integer maxDownloads;

    @Column(name = "download_count", nullable = false)
    private Integer downloadCount = 0;

    /**
     * The allowance is used. The document is still readable on screen until it
     * expires, which is the behaviour the specification describes: one
     * successful download, then view-only.
     */
    @Column(name = "is_view_only", nullable = false)
    private Boolean isViewOnly = false;

    @Column(name = "storage_bucket", length = 100)
    private String storageBucket;

    @Column(name = "storage_key", length = 500)
    private String storageKey;

    @Column(name = "file_checksum", length = 64)
    private String fileChecksum;

    @Column(name = "superseded_by_id")
    private Long supersededById;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoked_by", length = 100)
    private String revokedBy;

    @Column(name = "revoked_reason", length = 500)
    private String revokedReason;

    public boolean isExpired(LocalDateTime now) {
        return expiresAt.isBefore(now);
    }

    /** Current status, computed rather than trusted, since expiry passes silently. */
    public DocumentStatus effectiveStatus(LocalDateTime now) {
        if (status == DocumentStatus.REVOKED || status == DocumentStatus.SUPERSEDED) {
            return status;
        }
        return isExpired(now) ? DocumentStatus.EXPIRED : DocumentStatus.ACTIVE;
    }

    @Override
    public Long resolveCentreId() {
        return centre == null ? null : centre.getId();
    }
}
