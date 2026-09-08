package com.fnph.telepsychiatric.payment;

import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One discrepancy for Finance to work.
 *
 * Deliberately not auto-resolved. The specification says Finance performs no
 * manual activation of an ordinary successful payment, and the corollary is
 * that the system performs no automatic correction of an extraordinary one. A
 * mismatch is a decision, and it needs a person and a recorded reason.
 */
@Entity
@Table(name = "reconciliation_exceptions")
@Getter
@Setter
public class ReconciliationException extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    private ReconciliationRun run;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @Enumerated(EnumType.STRING)
    @Column(name = "exception_type", nullable = false, length = 30)
    private ReconciliationExceptionType exceptionType;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "expected_amount", precision = 19, scale = 2)
    private BigDecimal expectedAmount;

    @Column(name = "reported_amount", precision = 19, scale = 2)
    private BigDecimal reportedAmount;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;
}
