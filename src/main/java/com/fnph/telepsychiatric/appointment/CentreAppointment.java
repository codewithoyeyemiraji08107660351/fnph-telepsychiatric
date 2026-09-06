package com.fnph.telepsychiatric.appointment;

import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.patient.CentrePatient;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "centre_appointments")
@Getter
@Setter
public class CentreAppointment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_patient_id", nullable = false)
    private CentrePatient centrePatient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id", nullable = false)
    private Center centre;

    @Column(name = "appointment_date", nullable = false)
    private LocalDateTime appointmentDateTime;

    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes = 30;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING_APPROVAL;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id")
    private Users doctor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pharmacy_id")
    private Users pharmacy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "laboratory_id")
    private Users laboratory;

    @Column(name = "room", length = 50)
    private String room;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "postponed_reason", columnDefinition = "TEXT")
    private String postponedReason;

    @Column(name = "returned_reason", columnDefinition = "TEXT")
    private String returnedReason;

    @Column(name = "join_url")
    private String joinUrl;

    @Column(name = "meeting_id")
    private String meetingId;

    @Column(name = "no_show_reason", columnDefinition = "TEXT")
    private String noShowReason;
}
