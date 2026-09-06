package com.fnph.telepsychiatric.consultation;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.patient.Patient;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "consultations")
@Getter
@Setter
public class Consultation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id", nullable = false)
    private Appointment appointment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

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

    @Column(name = "termination_reason")
    @Enumerated(EnumType.STRING)
    private TerminationReason terminationReason;

    @Column(name = "termination_note", columnDefinition = "TEXT")
    private String terminationNote;

    @Column(name = "identity_confirmed", nullable = false)
    private Boolean identityConfirmed = false;

    @Column(name = "has_audio_fallback", nullable = false)
    private Boolean hasAudioFallback = false;

    @Column(name = "recording_consent_given", nullable = false)
    private Boolean recordingConsentGiven = false;

    @Column(name = "recording_url")
    private String recordingUrl;

    @Column(name = "transcript_url")
    private String transcriptUrl;

    @Column(name = "escalation_instruction", columnDefinition = "TEXT")
    private String escalationInstruction;
}
