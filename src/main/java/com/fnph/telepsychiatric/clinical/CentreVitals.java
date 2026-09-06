package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.appointment.CentreAppointment;
import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.patient.CentrePatient;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "centre_vitals")
@Getter
@Setter
public class CentreVitals extends BaseEntity {

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
}
