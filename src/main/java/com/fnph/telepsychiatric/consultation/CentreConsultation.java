package com.fnph.telepsychiatric.consultation;

import com.fnph.telepsychiatric.appointment.CentreAppointment;
import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.patient.CentrePatient;
import com.fnph.telepsychiatric.user.Users;
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
@Table(name = "centre_consultations", indexes = {
        @Index(name = "idx_centre_consultations_appt", columnList = "centre_appointment_id")
})
@Getter
@Setter
@FilterDef(name = TenantFilters.CENTRE_TENANT,
           parameters = @ParamDef(name = TenantFilters.CENTRE_ID_PARAM, type = Long.class))
@Filter(name = TenantFilters.CENTRE_TENANT, condition = TenantFilters.CONDITION)
public class CentreConsultation extends BaseEntity implements TenantOwned {
    /**
     * Denormalised tenant key. Present so tenant isolation can be enforced as a
     * single repository-layer filter rather than a join that a future query
     * might omit. A scoping rule that depends on remembering a join fails open.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id", nullable = false)
    private Center centre;


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

    @Column(name = "outcome", length = 30)
    @Enumerated(EnumType.STRING)
    private Outcome outcome;

    @Column(name = "termination_reason", length = 50)
    @Enumerated(EnumType.STRING)
    private TerminationReason terminationReason;

    @Column(name = "termination_note", columnDefinition = "TEXT")
    private String terminationNote;

    @Column(name = "identity_confirmed", nullable = false)
    private Boolean identityConfirmed = false;

    @Column(name = "has_audio_fallback", nullable = false)
    private Boolean hasAudioFallback = false;

    @Column(name = "escalation_instruction", columnDefinition = "TEXT")
    private String escalationInstruction;

    @Column(name = "room_provider_id", length = 100)
    private String roomProviderId;

    @Column(name = "room_name", length = 120)
    private String roomName;

    @Column(name = "room_url", length = 300)
    private String roomUrl;

    @Column(name = "room_expires_at")
    private LocalDateTime roomExpiresAt;

    @Column(name = "room_created_at")
    private LocalDateTime roomCreatedAt;

    @Column(name = "room_deleted_at")
    private LocalDateTime roomDeletedAt;

    @Column(name = "remaining_seconds")
    private Integer remainingSeconds;

    @Column(name = "connection_issues", nullable = false)
    private Integer connectionIssues = 0;

    @Column(name = "scheduled_start_at")
    private LocalDateTime scheduledStartAt;

    @Column(name = "scheduled_end_at")
    private LocalDateTime scheduledEndAt;

    @Column(name = "warning_one_sent_at")
    private LocalDateTime warningOneSentAt;

    @Column(name = "warning_two_sent_at")
    private LocalDateTime warningTwoSentAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "terminated_by_id")
    private Users terminatedBy;

    @Column(name = "safety_action_taken", columnDefinition = "TEXT")
    private String safetyActionTaken;

    /**
     * Tenant key for isolation enforcement. Null means this row belongs to the
     * FNPH pathway rather than to a centre.
     */
    @Override
    public Long resolveCentreId() {
        return centre == null ? null : centre.getId();
    }
}
