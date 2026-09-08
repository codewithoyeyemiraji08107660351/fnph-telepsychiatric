package com.fnph.telepsychiatric.payment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    Optional<WalletTransaction> findFirstByWalletIdOrderByIdDesc(Long walletId);

    Page<WalletTransaction> findAllByWalletIdOrderByIdDesc(Long walletId, Pageable pageable);

    /** Derived balance, used to reconcile against the cached one on Wallet. */
    @Query("""
           select coalesce(sum(case when t.direction = com.fnph.telepsychiatric.payment.LedgerDirection.CREDIT
                                    then t.amount else -t.amount end), 0)
           from WalletTransaction t
           where t.wallet.id = :walletId
             and t.status = com.fnph.telepsychiatric.payment.LedgerEntryStatus.POSTED
           """)
    BigDecimal deriveBalance(@Param("walletId") Long walletId);

    boolean existsByTransactionReference(String reference);
}
