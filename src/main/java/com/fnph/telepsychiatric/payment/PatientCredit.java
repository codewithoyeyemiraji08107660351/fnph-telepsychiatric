package com.fnph.telepsychiatric.payment;

import com.fnph.telepsychiatric.common.ImmutableEntity;
import com.fnph.telepsychiatric.patient.Patient;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Money a patient paid that was not used, held against their next booking.
 *
 * Payment is non-refundable by decision. But a patient pays before the Hub
 * Coordinator approves, so a rejection would otherwise leave them out of pocket
 * for an administrative decision they had no part in, over a service they never
 * received. The money stays with FNPH and becomes a credit. Same cash position,
 * nobody penalised for a rejection.
 *
 * Append-only, for the same reason as the centre wallet ledger: balance is
 * derived from the entries, never a mutable field treated as truth. A patient
 * asking where their ten thousand naira went gets an answer from {@code reason}.
 */
@Entity
@Table(name = "patient_credits")
@Getter
@Setter
public class PatientCredit extends ImmutableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private LedgerDirection direction;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "source_payment_id")
    private Long sourcePaymentId;

    @Column(name = "applied_payment_id")
    private Long appliedPaymentId;

    @Column(name = "appointment_id")
    private Long appointmentId;

    /** Null means it does not expire. FNPH sets the window if they want one. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;
}
