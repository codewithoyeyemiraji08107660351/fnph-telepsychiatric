package com.fnph.telepsychiatric.payment;

import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "wallets")
@Getter
@Setter
public class Wallet extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id", nullable = false, unique = true)
    private Center centre;

    @Column(name = "balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "last_credited_at")
    private LocalDateTime lastCreditedAt;

    @Column(name = "last_debited_at")
    private LocalDateTime lastDebitedAt;

    @Column(name = "low_balance_warning_sent", nullable = false)
    private Boolean lowBalanceWarningSent = false;

    @Column(name = "critical_balance_warning_sent", nullable = false)
    private Boolean criticalBalanceWarningSent = false;

    @Column(name = "warning_threshold", precision = 19, scale = 2)
    private BigDecimal warningThreshold;

    @Column(name = "critical_threshold", precision = 19, scale = 2)
    private BigDecimal criticalThreshold;
}
