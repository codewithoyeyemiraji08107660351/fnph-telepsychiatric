package com.fnph.telepsychiatric.scheduling;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A request to cancel or move an appointment.
 *
 * Recorded even when refused. A patient who asked in time and was turned down
 * must be able to show that they asked, and {@code hoursNotice} is the number
 * the argument will be about.
 */
@Entity
@Table(name = "cancellation_requests")
@Getter
@Setter
public class CancellationRequest extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id", nullable = false)
    private Appointment appointment;

    @Column(name = "request_type", nullable = false, length = 20)
    private String requestType;

    @Column(name = "requested_by", nullable = false, length = 100)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proposed_slot_id")
    private Slot proposedSlot;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "SUBMITTED";

    @Column(name = "decided_by", length = 100)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_notes", length = 500)
    private String decisionNotes;

    /** How much notice was actually given, against the configured rule. */
    @Column(name = "hours_notice")
    private Integer hoursNotice;
}
