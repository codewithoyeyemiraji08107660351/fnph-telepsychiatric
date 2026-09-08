package com.fnph.telepsychiatric.payment;

import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.tenancy.TenantFilters;
import com.fnph.telepsychiatric.tenancy.TenantOwned;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The wallet balance and its thresholds were duplicated on Center. Those
 * columns are removed there; this is the single account record.
 *
 * balance is a cached projection reconciled against WalletTransaction. It is
 * never treated as the authoritative financial history. The ledger is.
 */
@Entity
@Table(name = "wallets", uniqueConstraints = {
        @UniqueConstraint(name = "uk_wallets_centre", columnNames = "centre_id")
})
@Getter
@Setter
@FilterDef(name = TenantFilters.CENTRE_TENANT,
           parameters = @ParamDef(name = TenantFilters.CENTRE_ID_PARAM, type = Long.class))
@Filter(name = TenantFilters.CENTRE_TENANT, condition = TenantFilters.CONDITION)
public class Wallet extends BaseEntity implements TenantOwned {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id", nullable = false, foreignKey = @ForeignKey(name = "fk_wallets_centre"))
    private Center centre;

    @Column(name = "balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "last_reconciled_at")
    private LocalDateTime lastReconciledAt;

    @Column(name = "last_credited_at")
    private LocalDateTime lastCreditedAt;

    @Column(name = "last_debited_at")
    private LocalDateTime lastDebitedAt;

    @Column(name = "warning_threshold", precision = 19, scale = 2)
    private BigDecimal warningThreshold;

    @Column(name = "critical_threshold", precision = 19, scale = 2)
    private BigDecimal criticalThreshold;

    @Column(name = "low_balance_warning_sent", nullable = false)
    private Boolean lowBalanceWarningSent = false;

    @Column(name = "critical_balance_warning_sent", nullable = false)
    private Boolean criticalBalanceWarningSent = false;

    /** Guards concurrent debits from simultaneous centre bookings. */
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
