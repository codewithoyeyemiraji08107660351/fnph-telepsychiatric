package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.appointment.CentreAppointment;
import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.patient.CentrePatient;
import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.tenancy.TenantFilters;
import com.fnph.telepsychiatric.tenancy.TenantOwned;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "centre_vitals")
@Getter
@Setter
@FilterDef(name = TenantFilters.CENTRE_TENANT,
           parameters = @ParamDef(name = TenantFilters.CENTRE_ID_PARAM, type = Long.class))
@Filter(name = TenantFilters.CENTRE_TENANT, condition = TenantFilters.CONDITION)
public class CentreVitals extends BaseEntity implements TenantOwned {
    /**
     * Denormalised tenant key. Present so tenant isolation can be enforced as a
     * single repository-layer filter rather than a join that a future query
     * might omit. A scoping rule that depends on remembering a join fails open.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id", nullable = false)
    private Center centre;


    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_patient_id", nullable = false)
    private CentrePatient centrePatient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_appointment_id")
    private CentreAppointment centreAppointment;

    @Column(name = "blood_pressure_systolic")
    private Integer bloodPressureSystolic;

    @Column(name = "blood_pressure_diastolic")
    private Integer bloodPressureDiastolic;

    @Column(name = "heart_rate")
    private Integer heartRate;

    @Column(name = "respiratory_rate")
    private Integer respiratoryRate;

    @Column(name = "temperature")
    private Double temperature;

    @Column(name = "weight_kg")
    private Double weightKg;

    @Column(name = "height_cm")
    private Double heightCm;

    @Column(name = "bmi")
    private Double bmi;

    @Column(name = "blood_oxygen")
    private Integer bloodOxygen;

    @Column(name = "blood_glucose")
    private Double bloodGlucose;

    @Column(name = "measured_at", nullable = false)
    private LocalDateTime measuredAt;

    @Column(name = "measurement_source", length = 50)
    private String measurementSource;

    @Column(name = "is_self_reported", nullable = false)
    private Boolean isSelfReported = true;

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
