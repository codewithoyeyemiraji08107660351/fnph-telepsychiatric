package com.fnph.telepsychiatric.appointment;

import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.patient.Patient;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "appointments", uniqueConstraints = {
        @UniqueConstraint(name = "uk_appointments_reference", columnNames = "reference")
}, indexes = {
        @Index(name = "idx_appointments_status", columnList = "status"),
        @Index(name = "idx_appointments_datetime", columnList = "appointment_date"),
        @Index(name = "idx_appointments_doctor", columnList = "doctor_id,appointment_date")
})
@Getter
@Setter
public class Appointment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

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

    /**
     * Kept as a label until the Room entity lands in the scheduling milestone.
     * Whether a room constrains concurrent appointments is still an open
     * question with FNPH.
     */
    @Column(name = "room", length = 50)
    private String room;

    @Column(name = "reference", nullable = false, length = 50)
    private String reference;

    /**
     * The slot this appointment occupies. Unique: one appointment per slot,
     * enforced by the database so double booking is impossible even if every
     * check above it were bypassed.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "slot_id")
    private com.fnph.telepsychiatric.scheduling.Slot slot;

    /** The payment that bought this time. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private com.fnph.telepsychiatric.payment.Payment payment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private com.fnph.telepsychiatric.scheduling.Room assignedRoom;

    /** When the slot hold lapses if payment has not completed. */
    @Column(name = "held_until")
    private LocalDateTime heldUntil;

    /**
     * When the consultation fee was taken from the patient's wallet.
     *
     * On approval, not on payment. Payment credits the wallet; approval spends
     * it. A rejected booking never reaches this, which is what leaves the
     * balance intact for the next attempt.
     */
    @Column(name = "wallet_debited_at")
    private LocalDateTime walletDebitedAt;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejected_reason", columnDefinition = "TEXT")
    private String rejectedReason;

    @Column(name = "join_url")
    private String joinUrl;

    @Column(name = "meeting_id")
    private String meetingId;

    @Column(name = "no_show_reason", columnDefinition = "TEXT")
    private String noShowReason;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancelled_by")
    private String cancelledBy;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    /**
     * Multidisciplinary team. CentreAppointment already carried these; the FNPH
     * pathway assigns the same team and was missing them.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pharmacist_id", foreignKey = @ForeignKey(name = "fk_appointments_pharmacist"))
    private Users pharmacist;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "laboratory_technician_id", foreignKey = @ForeignKey(name = "fk_appointments_lab"))
    private Users laboratoryTechnician;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "nurse_id", foreignKey = @ForeignKey(name = "fk_appointments_nurse"))
    private Users nurse;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "him_officer_id", foreignKey = @ForeignKey(name = "fk_appointments_him"))
    private Users himOfficer;

    @Column(name = "nursing_completed_at")
    private LocalDateTime nursingCompletedAt;

    @Column(name = "him_completed_at")
    private LocalDateTime himCompletedAt;

    /** Derived from room_open_lead_minutes. The client is never the source of truth. */
    @Column(name = "join_window_opens_at")
    private LocalDateTime joinWindowOpensAt;

    @Column(name = "scheduled_end_at")
    private LocalDateTime scheduledEndAt;

    @Column(name = "no_show_at")
    private LocalDateTime noShowAt;

    /** Guards two patients claiming the same time concurrently. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;
}
