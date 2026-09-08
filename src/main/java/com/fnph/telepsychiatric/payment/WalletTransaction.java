package com.fnph.telepsychiatric.payment;

import com.fnph.telepsychiatric.appointment.CentreAppointment;
import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.common.ImmutableEntity;
import com.fnph.telepsychiatric.tenancy.TenantFilters;
import com.fnph.telepsychiatric.tenancy.TenantOwned;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Append-only centre wallet ledger.
 *
 * Extends ImmutableEntity, so there is no updated_at and no soft delete. UPDATE
 * and DELETE are additionally revoked on this table at the database grant
 * level. Financial history is read from these rows, never derived from the
 * mutable balance field on Wallet.
 *
 * type and status previously shared the TransactionStatus enum, which mixed
 * direction with lifecycle. They are now LedgerDirection and LedgerEntryStatus.
 * appointmentReference was a loose String and is now a real reference.
 */
@Entity
@Table(name = "wallet_transactions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_wallet_tx_reference", columnNames = "transaction_reference")
}, indexes = {
        @Index(name = "idx_wallet_tx_centre", columnList = "centre_id,created_at")
})
@Getter
@Setter
@FilterDef(name = TenantFilters.CENTRE_TENANT,
           parameters = @ParamDef(name = TenantFilters.CENTRE_ID_PARAM, type = Long.class))
@Filter(name = TenantFilters.CENTRE_TENANT, condition = TenantFilters.CONDITION)
public class WalletTransaction extends ImmutableEntity implements TenantOwned {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id", nullable = false, foreignKey = @ForeignKey(name = "fk_wallet_tx_centre"))
    private Center centre;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false, foreignKey = @ForeignKey(name = "fk_wallet_tx_wallet"))
    private Wallet wallet;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_appointment_id", foreignKey = @ForeignKey(name = "fk_wallet_tx_appointment"))
    private CentreAppointment centreAppointment;

    @Column(name = "transaction_reference", nullable = false, length = 50)
    private String transactionReference;

    @Column(name = "direction", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    private LedgerDirection direction;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private LedgerEntryStatus status = LedgerEntryStatus.POSTED;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_before", nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceBefore;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "source", length = 50)
    private String source;

    /** A reversal points at the entry it reverses. Nothing is ever edited or removed. */
    @Column(name = "reverses_transaction_id")
    private Long reversesTransactionId;

    /**
     * posted_at and posted_by were removed. On an append-only ledger the moment
     * a row is written IS the moment it is posted, and the account that wrote it
     * IS the account that posted it. Two columns for one fact is two things that
     * can disagree. Use createdAt and createdBy from ImmutableEntity.
     */

    /**
     * Tenant key for isolation enforcement. Null means this row belongs to the
     * FNPH pathway rather than to a centre.
     */
    @Override
    public Long resolveCentreId() {
        return centre == null ? null : centre.getId();
    }
}
