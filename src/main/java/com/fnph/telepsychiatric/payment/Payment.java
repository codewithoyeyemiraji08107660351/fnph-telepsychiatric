package com.fnph.telepsychiatric.payment;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.patient.Patient;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
@Getter
@Setter
public class Payment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id")
    private Appointment appointment;

    @Column(name = "payment_type", nullable = false)
    @Enumerated(EnumType.STRING)
    private TransactionStatus paymentType;

    @Column(name = "reference", unique = true, nullable = false, length = 50)
    private String reference;

    @Column(name = "remita_reference", length = 50)
    private String remitaReference;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "NGN";

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "payment_date")
    private LocalDateTime paymentDate;

    @Column(name = "webhook_received_at")
    private LocalDateTime webhookReceivedAt;

    @Column(name = "webhook_payload_hash")
    private String webhookPayloadHash;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "reconciliation_status", length = 20)
    private String reconciliationStatus;

    @Column(name = "reconciliation_notes", columnDefinition = "TEXT")
    private String reconciliationNotes;

    @Column(name = "is_manually_reconciled", nullable = false)
    private Boolean isManuallyReconciled = false;
}
