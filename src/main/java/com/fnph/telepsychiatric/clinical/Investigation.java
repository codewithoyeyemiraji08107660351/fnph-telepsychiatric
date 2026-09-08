package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.consultation.CentreConsultation;
import com.fnph.telepsychiatric.consultation.Consultation;
import com.fnph.telepsychiatric.patient.CentrePatient;
import com.fnph.telepsychiatric.patient.Patient;
import com.fnph.telepsychiatric.user.Users;
import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.tenancy.TenantFilters;
import com.fnph.telepsychiatric.tenancy.TenantOwned;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Same treatment as Prescription: header plus items, dual-owned across both
 * pathways, with document and review concerns moved out.
 *
 * The doctor workspace offers multiple selectable panels on one request, which
 * the previous single investigationType column could not hold.
 */
@Entity
@Table(name = "investigations", uniqueConstraints = {
        @UniqueConstraint(name = "uk_investigations_issue_number", columnNames = "issue_number")
}, indexes = {
        @Index(name = "idx_investigations_status", columnList = "status"),
        @Index(name = "idx_investigations_patient", columnList = "patient_id")
})
@Getter
@Setter
@FilterDef(name = TenantFilters.CENTRE_TENANT,
           parameters = @ParamDef(name = TenantFilters.CENTRE_ID_PARAM, type = Long.class))
@Filter(name = TenantFilters.CENTRE_TENANT, condition = TenantFilters.CONDITION)
public class Investigation extends BaseEntity implements TenantOwned {
    /**
     * Denormalised tenant key. NULL means this row belongs to the FNPH pathway;
     * set means it belongs to a Centre. The centre repository filter is
     * "centre_id = :centreId", which excludes FNPH rows automatically instead of
     * relying on every query to remember.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id")
    private Center centre;

    /** The bundle this is released in. Nothing reaches the patient alone. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bundle_id")
    private ReleaseBundle bundle;


    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consultation_id", foreignKey = @ForeignKey(name = "fk_investigations_consultation"))
    private Consultation consultation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_consultation_id", foreignKey = @ForeignKey(name = "fk_investigations_centre_consultation"))
    private CentreConsultation centreConsultation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", foreignKey = @ForeignKey(name = "fk_investigations_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_patient_id", foreignKey = @ForeignKey(name = "fk_investigations_centre_patient"))
    private CentrePatient centrePatient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false, foreignKey = @ForeignKey(name = "fk_investigations_doctor"))
    private Users doctor;

    @Column(name = "issue_number", nullable = false, length = 50)
    private String issueNumber;

    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private ClinicalDocumentStatus status = ClinicalDocumentStatus.DRAFT;

    @Column(name = "not_required", nullable = false)
    private Boolean notRequired = false;

    @Column(name = "not_required_reason", length = 500)
    private String notRequiredReason;

    /** Clinical information supplied to the laboratory. */
    @Column(name = "clinical_information", columnDefinition = "TEXT")
    private String clinicalInformation;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "validity_days", nullable = false)
    private Integer validityDays = 7;

    @Column(name = "supersedes_id")
    private Long supersedesId;

    @OneToMany(mappedBy = "investigation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequence ASC")
    private List<InvestigationItem> items = new ArrayList<>();

    /**
     * Tenant key for isolation enforcement. Null means this row belongs to the
     * FNPH pathway rather than to a centre.
     */
    @Override
    public Long resolveCentreId() {
        return centre == null ? null : centre.getId();
    }
}
