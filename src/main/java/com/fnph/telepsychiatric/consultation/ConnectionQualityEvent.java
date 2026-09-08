package com.fnph.telepsychiatric.consultation;

import com.fnph.telepsychiatric.common.ImmutableEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * How the call actually behaved.
 *
 * "The call kept dropping" is the most common complaint about telemedicine
 * anywhere. Without numbers it is one person's word against another's about
 * whether the service or the line was at fault, and a clinician terminating a
 * session for unsafe connectivity needs something to point at.
 */
@Entity
@Table(name = "connection_quality_events")
@Getter
@Setter
public class ConnectionQualityEvent extends ImmutableEntity {

    @Column(name = "consultation_id")
    private Long consultationId;

    @Column(name = "centre_consultation_id")
    private Long centreConsultationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "participant_role", nullable = false, length = 20)
    private ParticipantRole participantRole;

    @Column(name = "round_trip_ms")
    private Integer roundTripMs;

    @Column(name = "packet_loss_percent", precision = 5, scale = 2)
    private BigDecimal packetLossPercent;

    @Column(name = "video_receive_quality", length = 20)
    private String videoReceiveQuality;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;
}
