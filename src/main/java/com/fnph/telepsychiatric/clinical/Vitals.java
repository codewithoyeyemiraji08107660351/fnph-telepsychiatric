package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.patient.Patient;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "vitals")
@Getter
@Setter
public class Vitals extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id")
    private Appointment appointment;

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

    @Column(name = "nurse_verified", nullable = false)
    private Boolean nurseVerified = false;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "verified_by")
    private String verifiedBy;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
