package com.fnph.telepsychiatric.payment;

import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** One pass comparing this system's payment records against Remita. */
@Entity
@Table(name = "reconciliation_runs")
@Getter
@Setter
public class ReconciliationRun extends BaseEntity {

    @Column(name = "run_type", nullable = false, length = 20)
    private String runType;

    @Column(name = "period_start", nullable = false)
    private LocalDateTime periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDateTime periodEnd;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "transactions_checked", nullable = false)
    private Integer transactionsChecked = 0;

    @Column(name = "matched_count", nullable = false)
    private Integer matchedCount = 0;

    @Column(name = "exception_count", nullable = false)
    private Integer exceptionCount = 0;

    @Column(name = "run_by", length = 100)
    private String runBy;
}
