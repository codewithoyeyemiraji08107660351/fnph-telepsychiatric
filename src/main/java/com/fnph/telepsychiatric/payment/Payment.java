package com.fnph.telepsychiatric.payment;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.patient.Patient;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * paymentType was declared as TransactionStatus, a ledger enum. It is now
 * PaymentPurpose.
 *
 * webhookReceivedAt and webhookPayloadHash moved to WebhookInbox. Exactly-once
 * processing needs a unique constraint on the raw event, not a nullable column
 * on the payment row.
 *
 * Payment is created before slot selection, so it can exist without an
 * appointment. Refund and transfer rules on a rejected booking are an open
 * governance decision; refundedAt and refundReference are here to receive it.
 */
@Entity
@Table(name = "payments", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payments_reference", columnNames = "reference")
}, indexes = {
        @Index(name = "idx_payments_status", columnList = "status"),
        @Index(name = "idx_payments_patient", columnList = "patient_id"),
        @Index(name = "idx_payments_remita_ref", columnList = "remita_reference")
})
@Getter
@Setter
public class Payment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", foreignKey = @ForeignKey(name = "fk_payments_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id", foreignKey = @ForeignKey(name = "fk_payments_appointment"))
    private Appointment appointment;

    @Column(name = "purpose", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private PaymentPurpose purpose;

    @Column(name = "reference", nullable = false, length = 50)
    private String reference;

    @Column(name = "remita_reference", length = 100)
    private String remitaReference;

    /**
     * Remita Retrieval Reference, issued at initiation.
     *
     * The number the patient quotes at any payment channel, and therefore the
     * number every support call about a payment will be about. Unique.
     */
    @Column(name = "rrr", length = 50)
    private String rrr;

    @Column(name = "payment_channel", length = 50)
    private String paymentChannel;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /** Credit carried forward from an earlier non-refunded payment. */
    @Column(name = "credit_applied", nullable = false, precision = 19, scale = 2)
    private BigDecimal creditApplied = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "NGN";

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "initiated_at")
    private LocalDateTime initiatedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "payment_date")
    private LocalDateTime paymentDate;

    /** Set only by server-side verification against Remita, never by a browser return page. */
    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "initiation_response", columnDefinition = "TEXT")
    private String initiationResponse;

    @Column(name = "last_verified_at")
    private LocalDateTime lastVerifiedAt;

    @Column(name = "verification_attempts", nullable = false)
    private Integer verificationAttempts = 0;

    /**
     * Remita reported an amount other than the order's.
     *
     * Never auto-accepted. Paying less than the fee is not a rounding issue,
     * and paying more means the patient is owed a credit. Either way it is a
     * Finance decision.
     */
    @Column(name = "amount_mismatch", nullable = false)
    private Boolean amountMismatch = false;

    @Column(name = "reported_amount", precision = 19, scale = 2)
    private BigDecimal reportedAmount;

    @Column(name = "reconciliation_status", length = 20)
    private String reconciliationStatus;

    @Column(name = "reconciliation_notes", columnDefinition = "TEXT")
    private String reconciliationNotes;

    /**
     * Finance monitors and reconciles but never manually activates an ordinary
     * successful payment. This flag records the exception path only.
     */
    @Column(name = "is_manually_reconciled", nullable = false)
    private Boolean isManuallyReconciled = false;

    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    @Column(name = "refund_reference", length = 100)
    private String refundReference;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;
}
