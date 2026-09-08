package com.fnph.telepsychiatric.consultation;

import com.fnph.telepsychiatric.common.ImmutableEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Who joined, when, and how the call ended. Append-only.
 *
 * The evidence behind a no-show, a disputed attendance, and any later question
 * about whether a consultation actually took place. A patient charged for a
 * session they say never happened is settled here.
 *
 * providerEventId is unique so a repeated provider webhook cannot record the
 * same join twice and turn one attendance into two.
 */
@Entity
@Table(name = "attendance_events")
@Getter
@Setter
public class AttendanceEvent extends ImmutableEntity {

    @Column(name = "consultation_id")
    private Long consultationId;

    @Column(name = "centre_consultation_id")
    private Long centreConsultationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "participant_role", nullable = false, length = 20)
    private ParticipantRole participantRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private AttendanceEventType eventType;

    @Column(name = "modality", length = 20)
    private String modality;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "provider_event_id", length = 150)
    private String providerEventId;

    @Column(name = "provider_session_id", length = 150)
    private String providerSessionId;

    @Column(name = "details", length = 500)
    private String details;
}
