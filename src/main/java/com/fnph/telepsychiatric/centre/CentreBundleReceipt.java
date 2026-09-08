package com.fnph.telepsychiatric.centre;

import com.fnph.telepsychiatric.appointment.CentreAppointment;
import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.clinical.ReleaseBundle;
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
 * A released bundle sitting in a centre's incoming queue.
 *
 * Separate from the bundle itself because the two answer different questions.
 * The bundle is FNPH's record of what the consultation produced. This is the
 * centre's record of what they did with it, and "was this acted on" is a
 * question about the centre, not about the hospital.
 */
@Entity
@Table(name = "centre_bundle_receipts")
@FilterDef(name = TenantFilters.CENTRE_TENANT,
           parameters = @ParamDef(name = TenantFilters.CENTRE_ID_PARAM, type = Long.class))
@Filter(name = TenantFilters.CENTRE_TENANT, condition = TenantFilters.CONDITION)
@Getter
@Setter
public class CentreBundleReceipt extends BaseEntity implements TenantOwned {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id", nullable = false)
    private Center centre;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bundle_id", nullable = false)
    private ReleaseBundle bundle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_appointment_id", nullable = false)
    private CentreAppointment centreAppointment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_patient_id", nullable = false)
    private CentrePatient centrePatient;

    @Column(name = "delivered_at", nullable = false)
    private LocalDateTime deliveredAt;

    @Column(name = "first_opened_at")
    private LocalDateTime firstOpenedAt;

    @Column(name = "first_opened_by", length = 100)
    private String firstOpenedBy;

    @Column(name = "treated_at")
    private LocalDateTime treatedAt;

    @Column(name = "treated_by", length = 100)
    private String treatedBy;

    @Column(name = "treatment_notes", columnDefinition = "TEXT")
    private String treatmentNotes;

    public boolean isOutstanding() {
        return treatedAt == null;
    }

    @Override
    public Long resolveCentreId() {
        return centre == null ? null : centre.getId();
    }
}
