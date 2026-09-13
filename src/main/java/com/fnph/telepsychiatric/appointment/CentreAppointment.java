package com.fnph.telepsychiatric.appointment;

import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.patient.CentrePatient;
import com.fnph.telepsychiatric.tenancy.TenantFilters;
import com.fnph.telepsychiatric.tenancy.TenantOwned;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;

@Entity
@Table(name = "centre_appointments", uniqueConstraints = {
        @UniqueConstraint(name = "uk_centre_appointments_reference", columnNames = "reference")
}, indexes = {
        @Index(name = "idx_centre_appointments_centre", columnList = "centre_id,appointment_date"),
        @Index(name = "idx_centre_appointments_status", columnList = "status")
})
@Getter
@Setter
@Filter(name = TenantFilters.CENTRE_TENANT, condition = TenantFilters.CONDITION)
public class CentreAppointment extends BaseEntity implements TenantOwned {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_patient_id", nullable = false)
    private CentrePatient centrePatient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id", nullable = false)
    private Center centre;

    /**
     * Named to match the column. The property name is what derived queries and
     * JPQL resolve against, so {@code appointmentDateTime} over a column called
     * {@code appointment_date} produces a repository that compiles and then
     * fails at context startup.
     */
    @Column(name = "appointment_date", nullable = false)
    private LocalDateTime appointmentDate;

    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes = 30;

    /**
     * A centre request goes straight to the Hub Coordinator. There is no
     * patient payment step on this pathway; the centre wallet is debited at
     * approval instead.
     */
    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status = Status.AWAITING_APPROVAL;

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

    @Column(name = "reference", nullable = false, length = 50)
    private String reference;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "postponed_reason", columnDefinition = "TEXT")
    private String postponedReason;

    @Column(name = "returned_reason", columnDefinition = "TEXT")
    private String returnedReason;

    /** The referral this consultation is for. One per visit, not per patient. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referral_id")
    private com.fnph.telepsychiatric.centre.CentreReferral referral;

    /** Unique: one centre appointment per slot, enforced by the database. */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "slot_id")
    private com.fnph.telepsychiatric.scheduling.Slot slot;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private com.fnph.telepsychiatric.scheduling.Room roomEntity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "him_id")
    private Users him;

    /**
     * The ledger entry that paid for this booking.
     *
     * Present only after approval, which is what makes "was this booking
     * charged" answerable from the booking itself.
     */
    @Column(name = "wallet_transaction_id")
    private Long walletTransactionId;

    @Column(name = "wallet_debited_at")
    private LocalDateTime walletDebitedAt;

    @Column(name = "join_url")
    private String joinUrl;

    @Column(name = "meeting_id")
    private String meetingId;

    @Column(name = "no_show_reason", columnDefinition = "TEXT")
    private String noShowReason;

    @Column(name = "join_window_opens_at")
    private LocalDateTime joinWindowOpensAt;

    @Column(name = "scheduled_end_at")
    private LocalDateTime scheduledEndAt;

    @Column(name = "no_show_at")
    private LocalDateTime noShowAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    /**
     * Tenant key for isolation enforcement. Null means this row belongs to the
     * FNPH pathway rather than to a centre.
     */
    @Override
    public Long resolveCentreId() {
        return centre == null ? null : centre.getId();
    }
}