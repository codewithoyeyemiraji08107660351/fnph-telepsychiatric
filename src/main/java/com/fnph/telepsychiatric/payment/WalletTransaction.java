package com.fnph.telepsychiatric.payment;

import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "wallet_transactions")
@Getter
@Setter
public class WalletTransaction extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id", nullable = false)
    private Center centre;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Column(name = "transaction_reference", unique = true, nullable = false, length = 50)
    private String transactionReference;

    @Column(name = "type", nullable = false)
    @Enumerated(EnumType.STRING)
    private TransactionStatus type;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_before", nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceBefore;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private TransactionStatus status = TransactionStatus.PENDING;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "appointment_reference", length = 50)
    private String appointmentReference;

    @Column(name = "source", length = 50)
    private String source;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;
}
