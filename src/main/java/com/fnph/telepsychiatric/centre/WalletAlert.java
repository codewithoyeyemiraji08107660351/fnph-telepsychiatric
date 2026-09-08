package com.fnph.telepsychiatric.centre;

import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.payment.Wallet;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A low-balance warning to Finance.
 *
 * Recorded so an alert fires once per threshold crossing rather than on every
 * booking below it. Firing on each booking would produce dozens of identical
 * warnings in a morning and train Finance to ignore all of them, which is worse
 * than not alerting at all.
 *
 * Not visible to centres. They see consultation and utilisation counts, never
 * wallet amounts.
 */
@Entity
@Table(name = "wallet_alerts")
@Getter
@Setter
public class WalletAlert extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id", nullable = false)
    private Center centre;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    /** WARNING at the 20 percent threshold, CRITICAL at 10. */
    @Column(name = "alert_level", nullable = false, length = 20)
    private String alertLevel;

    @Column(name = "balance_at_alert", nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAtAlert;

    @Column(name = "threshold_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal thresholdAmount;

    @Column(name = "raised_at", nullable = false)
    private LocalDateTime raisedAt;

    /** Set when a top-up takes the balance back above the threshold. */
    @Column(name = "cleared_at")
    private LocalDateTime clearedAt;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "acknowledged_by", length = 100)
    private String acknowledgedBy;
}
