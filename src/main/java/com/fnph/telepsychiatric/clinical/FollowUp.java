package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.consultation.CentreConsultation;
import com.fnph.telepsychiatric.consultation.Consultation;
import com.fnph.telepsychiatric.consultation.Modality;
import com.fnph.telepsychiatric.patient.CentrePatient;
import com.fnph.telepsychiatric.patient.Patient;
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
import java.time.LocalTime;

/**
 * Dual-owned across both pathways. status moved off the shared clinical Status
 * enum, which held eleven values most of which were invalid here.
 *
 * A follow-up is a clinical recommendation only. It does not consume a slot.
 * The Hub Coordinator confirms availability and the patient or centre receives
 * the approved schedule separately.
 */
@Entity
@Table(name = "follow_ups", indexes = {
        @Index(name = "idx_follow_ups_status", columnList = "status")
})
@Getter
@Setter
@FilterDef(name = TenantFilters.CENTRE_TENANT,
           parameters = @ParamDef(name = TenantFilters.CENTRE_ID_PARAM, type = Long.class))
@Filter(name = TenantFilters.CENTRE_TENANT, condition = TenantFilters.CONDITION)
public class FollowUp extends BaseEntity implements TenantOwned {
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
    @JoinColumn(name = "consultation_id", foreignKey = @ForeignKey(name = "fk_follow_ups_consultation"))
    private Consultation consultation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_consultation_id", foreignKey = @ForeignKey(name = "fk_follow_ups_centre_consultation"))
    private CentreConsultation centreConsultation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", foreignKey = @ForeignKey(name = "fk_follow_ups_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_patient_id", foreignKey = @ForeignKey(name = "fk_follow_ups_centre_patient"))
    private CentrePatient centrePatient;

    @Column(name = "recommendation", columnDefinition = "TEXT")
    private String recommendation;

    @Column(name = "review_interval", length = 50)
    private String reviewInterval;

    @Column(name = "expected_timeframe", length = 50)
    private String expectedTimeframe;

    @Column(name = "preferred_date")
    private LocalDate preferredDate;

    @Column(name = "preferred_time")
    private LocalTime preferredTime;

    @Column(name = "consultation_mode", length = 30)
    @Enumerated(EnumType.STRING)
    private Modality consultationMode = Modality.VIDEO;

    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private FollowUpStatus status = FollowUpStatus.RECOMMENDED;

    @Column(name = "scheduled_date")
    private LocalDate scheduledDate;

    @Column(name = "completed_date")
    private LocalDate completedDate;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /**
     * Tenant key for isolation enforcement. Null means this row belongs to the
     * FNPH pathway rather than to a centre.
     */
    @Override
    public Long resolveCentreId() {
        return centre == null ? null : centre.getId();
    }
}
