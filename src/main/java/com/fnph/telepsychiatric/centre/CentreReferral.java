package com.fnph.telepsychiatric.centre;

import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.patient.CentrePatient;
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
 * One referral from a Centre of Excellence.
 *
 * <h2>Per visit, not per patient</h2>
 *
 * These fields used to live on the patient row. A patient seen three times over
 * a year has three presenting conditions, and holding them on the patient means
 * each new referral silently overwrites the last. The clinical history is the
 * reason the consultation happens; losing it loses the point.
 *
 * <h2>Consent is per referral too</h2>
 *
 * A patient consented to a consultation in March. That is not consent to one in
 * September.
 *
 * <h2>The FNPH EHR number stays narrative</h2>
 *
 * A centre patient who also holds an FNPH record is still strictly a centre
 * referral. The offline FNPH record is never linked or retrieved, so a number
 * mentioned in the referral text is text.
 */
@Entity
@Table(name = "centre_referrals")
@FilterDef(name = TenantFilters.CENTRE_TENANT,
           parameters = @ParamDef(name = TenantFilters.CENTRE_ID_PARAM, type = Long.class))
@Filter(name = TenantFilters.CENTRE_TENANT, condition = TenantFilters.CONDITION)
@Getter
@Setter
public class CentreReferral extends BaseEntity implements TenantOwned {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id", nullable = false)
    private Center centre;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_patient_id", nullable = false)
    private CentrePatient centrePatient;

    @Column(name = "reference", nullable = false, length = 50)
    private String reference;

    @Column(name = "referral_reason", nullable = false, columnDefinition = "TEXT")
    private String referralReason;

    @Column(name = "assessment", columnDefinition = "TEXT")
    private String assessment;

    @Column(name = "current_condition", columnDefinition = "TEXT")
    private String currentCondition;

    @Column(name = "relevant_medicines", columnDefinition = "TEXT")
    private String relevantMedicines;

    @Column(name = "previous_results", columnDefinition = "TEXT")
    private String previousResults;

    @Column(name = "consent_version", length = 20)
    private String consentVersion;

    @Column(name = "consent_accepted_at")
    private LocalDateTime consentAcceptedAt;

    /** Who at the centre witnessed the patient consenting. */
    @Column(name = "consent_accepted_by", length = 150)
    private String consentAcceptedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "urgency", nullable = false, length = 20)
    private ReferralUrgency urgency = ReferralUrgency.ROUTINE;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReferralStatus status = ReferralStatus.DRAFT;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "submitted_by", length = 100)
    private String submittedBy;

    @Override
    public Long resolveCentreId() {
        return centre == null ? null : centre.getId();
    }
}
