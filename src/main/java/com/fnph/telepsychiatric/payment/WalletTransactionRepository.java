package com.fnph.telepsychiatric.payment;

import com.fnph.telepsychiatric.tenancy.UnscopedQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The centre wallet ledger. Append-only.
 *
 * <h2>Keyed by wallet, and the wallet decides the centre</h2>
 *
 * Every method takes a walletId the caller has already resolved, and a Wallet
 * belongs to exactly one centre, so the centre is settled before these are
 * reached. A centre never reads this repository at all: the specification is
 * explicit that centres do not see wallet balances, and Finance holds that
 * view.
 */
public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "walletId comes from a Wallet resolved by Finance; a Wallet belongs to "
                    + "one centre, so the centre is decided before this is called")
    Page<WalletTransaction> findAllByWalletIdOrderByIdDesc(Long walletId, Pageable pageable);

    /** Derived balance, used to reconcile against the cached one on Wallet. */
    @Query("""
           select coalesce(sum(case when t.direction = com.fnph.telepsychiatric.payment.LedgerDirection.CREDIT
                                    then t.amount else -t.amount end), 0)
           from WalletTransaction t
           where t.wallet.id = :walletId
             and t.status = com.fnph.telepsychiatric.payment.LedgerEntryStatus.POSTED
           """)
    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "walletId comes from a Wallet already resolved in CentreWalletService; "
                    + "the sum is over one wallet's own entries")
    BigDecimal deriveBalance(@Param("walletId") Long walletId);

    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SECRET,
            detail = "transaction reference is a Finance-supplied funding instrument "
                    + "identifier, globally unique by uk_wallet_tx_reference; the caller "
                    + "verifies the wallet matches before returning the entry")
    Optional<WalletTransaction> findByTransactionReference(String reference);

    /**
     * Posted balance of one wallet as it stood at an instant: every posted
     * entry created before it. Gives the opening and closing balances of an
     * export period from the ledger itself rather than from the cached figure,
     * which only knows the present.
     */
    @Query("""
           select coalesce(sum(case when t.direction = com.fnph.telepsychiatric.payment.LedgerDirection.CREDIT
                                    then t.amount else -t.amount end), 0)
           from WalletTransaction t
           where t.wallet.id = :walletId
             and t.status = com.fnph.telepsychiatric.payment.LedgerEntryStatus.POSTED
             and t.createdAt < :before
           """)
    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "walletId comes from a Wallet Finance resolved per centre for the export; "
                    + "the sum is over that one wallet's own entries")
    BigDecimal balanceBefore(@Param("walletId") Long walletId,
                             @Param("before") LocalDateTime before);

    /**
     * Every centre's ledger entries in a half-open window, for the Finance
     * export. Across centres by design: Finance holds the hospital-wide view
     * of wallets, and centres never read this repository.
     */
    @Query("""
           select t from WalletTransaction t
           join fetch t.centre
           where t.createdAt >= :from and t.createdAt < :to
           order by t.createdAt asc, t.id asc
           """)
    @UnscopedQuery(value = UnscopedQuery.Reason.HOSPITAL_QUEUE,
            detail = "Finance export across every centre wallet for a period; reached only "
                    + "through finance_report.read, which no centre role holds")
    List<WalletTransaction> findAllBetweenForExport(@Param("from") LocalDateTime from,
                                                       @Param("to") LocalDateTime to,
                                                       Pageable pageable);
}
