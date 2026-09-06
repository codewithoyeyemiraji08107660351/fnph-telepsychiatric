package com.fnph.telepsychiatric.consultation;

import com.fnph.telepsychiatric.appointment.CentreAppointment;
import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.patient.CentrePatient;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "centre_consultations")
@Getter
@Setter
public class CentreConsultation {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_appointment_id", nullable = false)
    private CentreAppointment centreAppointment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_patient_id", nullable = false)
    private CentrePatient centrePatient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false)
    private Users doctor;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "modality", nullable = false)
    @Enumerated(EnumType.STRING)
    private Modality modality = Modality.VIDEO;

    @Column(name = "outcome")
    @Enumerated(EnumType.STRING)
    private Outcome outcome;
}
