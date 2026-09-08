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
@Table(name = "consultations", indexes = {
        @Index(name = "idx_consultations_appointment", columnList = "appointment_id"),
        @Index(name = "idx_consultations_doctor", columnList = "doctor_id")
})
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

    /**
     * Recording is disabled by default and stays off until FNPH approves
     * consent, retention, access, data location and deletion policy. The
     * artefact URLs previously stored here move to dedicated Recording and
     * Transcript entities so consent and deletion are modelled rather than
     * implied by a nullable string.
     */
    @Column(name = "recording_consent_given", nullable = false)
    private Boolean recordingConsentGiven = false;

    @Column(name = "escalation_instruction", columnDefinition = "TEXT")
    private String escalationInstruction;

    @Column(name = "room_provider_id", length = 100)
    private String roomProviderId;

    @Column(name = "room_name", length = 120)
    private String roomName;

    @Column(name = "room_url", length = 300)
    private String roomUrl;

    /** The provider deletes the room at this moment, whatever this system does. */
    @Column(name = "room_expires_at")
    private LocalDateTime roomExpiresAt;

    @Column(name = "room_created_at")
    private LocalDateTime roomCreatedAt;

    @Column(name = "room_deleted_at")
    private LocalDateTime roomDeletedAt;

    @Column(name = "scheduled_start_at")
    private LocalDateTime scheduledStartAt;

    @Column(name = "scheduled_end_at")
    private LocalDateTime scheduledEndAt;

    @Column(name = "patient_joined_at")
    private LocalDateTime patientJoinedAt;

    @Column(name = "doctor_joined_at")
    private LocalDateTime doctorJoinedAt;

    /** Two independently configured warnings, not one. */
    @Column(name = "warning_one_sent_at")
    private LocalDateTime warningOneSentAt;

    @Column(name = "warning_two_sent_at")
    private LocalDateTime warningTwoSentAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "terminated_by_id", foreignKey = @ForeignKey(name = "fk_consultations_terminated_by"))
    private Users terminatedBy;

    @Column(name = "safety_action_taken", columnDefinition = "TEXT")
    private String safetyActionTaken;

    /**
     * Seconds left, counted from the fixed slot end.
     *
     * Never from first join. A patient arriving five minutes late gets
     * twenty-five minutes, because extending this session shortens the next
     * one and that patient had nothing to do with the delay.
     */
    @Column(name = "remaining_seconds")
    private Integer remainingSeconds;

    @Column(name = "connection_issues", nullable = false)
    private Integer connectionIssues = 0;
}
