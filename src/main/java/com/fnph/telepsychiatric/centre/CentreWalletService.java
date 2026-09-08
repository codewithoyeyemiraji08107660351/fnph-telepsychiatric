package com.fnph.telepsychiatric.centre;

import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.configuration.ConfigurationKeys;
import com.fnph.telepsychiatric.configuration.ConfigurationService;
import com.fnph.telepsychiatric.notification.InAppNotificationService;
import com.fnph.telepsychiatric.notification.NotificationType;
import com.fnph.telepsychiatric.payment.*;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.security.crypto.Tokens;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * The centre prepaid wallet.
 *
 * <h2>Debited at approval</h2>
 *
 * The specification leaves the timing open: at approval, or at completed
 * consultation. Approval is chosen because that is the moment FNPH commits a
 * doctor, a room and a multidisciplinary team to a slot no other centre can
 * then use. A no-show after that costs the hospital exactly as much as an
 * attended session.
 *
 * A booking rejected or returned never reaches approval and never debits.
 *
 * <h2>Balance is derived, never trusted</h2>
 *
 * The ledger is the record. The cached balance on the wallet row exists so a
 * dashboard does not sum a year of entries on every page load, and it is
 * reconciled against the ledger rather than believed.
 *
 * <h2>Centres never see amounts</h2>
 *
 * Finance credits the wallet from programme funding and sees the balance. The
 * centre sees consultation and utilisation counts. Nothing in this class is
 * reachable by a centre role.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CentreWalletService {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository ledgerRepository;
    private final WalletAlertRepository alertRepository;
    private final ConfigurationService configuration;
    private final InAppNotificationService notifications;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public BigDecimal balanceOf(Long centreId) {
        return walletRepository.findByCentreId(centreId)
                .map(w -> ledgerRepository.deriveBalance(w.getId()))
                .orElse(BigDecimal.ZERO);
    }

    /** Whether an approval can be paid for. Checked before assigning anyone. */
    @Transactional(readOnly = true)
    public boolean canCoverBooking(Long centreId) {
        BigDecimal charge = configuration.getDecimal(ConfigurationKeys.CENTRE_BOOKING_CHARGE_NGN);
        return balanceOf(centreId).compareTo(charge) >= 0;
    }

    /**
     * Credits a centre wallet from programme funding.
     *
     * Finance only. Clears any open low-balance alert the top-up resolves, so
     * Finance is not looking at a warning about a wallet they have just filled.
     */
    @Transactional
    public WalletTransaction credit(Long centreId, BigDecimal amount, String reference,
                                    String description) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("A credit must be a positive amount");
        }
        Wallet wallet = walletRepository.findByCentreId(centreId)
                .orElseThrow(() -> new IllegalArgumentException("That centre has no wallet"));

        BigDecimal before = ledgerRepository.deriveBalance(wallet.getId());
        BigDecimal after = before.add(amount);

        WalletTransaction entry = new WalletTransaction();
        entry.setCentre(wallet.getCentre());
        entry.setWallet(wallet);
        entry.setTransactionReference(reference == null
                ? "CR-" + Tokens.generateRecoveryCode().replace("-", "") : reference);
        entry.setDirection(LedgerDirection.CREDIT);
        entry.setStatus(LedgerEntryStatus.POSTED);
        entry.setAmount(amount);
        entry.setBalanceBefore(before);
        entry.setBalanceAfter(after);
        entry.setDescription(description);
        entry.setSource("PROGRAMME_FUNDING");
        WalletTransaction saved = ledgerRepository.save(entry);

        wallet.setBalance(after);
        wallet.setLastCreditedAt(LocalDateTime.now());
        walletRepository.save(wallet);

        clearAlertsAbove(wallet, after);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.WALLET_CREDITED)
                .entityType("Wallet")
                .entityId(wallet.getId())
                .details("NGN %s credited, balance now %s".formatted(amount, after))
                .reason(description)
                .build());

        log.info("Centre {} wallet credited {}, balance {}", centreId, amount, after);
        return saved;
    }

    /**
     * Debits the booking charge at approval.
     *
     * Refuses rather than allowing a negative balance. A centre booking that
     * proceeds with no funding is a consultation the hospital delivers and
     * cannot account for, and the specification is explicit that each approved
     * booking creates an immutable ledger deduction.
     */
    @Transactional
    public WalletTransaction debitForBooking(Center centre, Long centreAppointmentId,
                                             String appointmentReference) {
        BigDecimal charge = configuration.getDecimal(ConfigurationKeys.CENTRE_BOOKING_CHARGE_NGN);

        Wallet wallet = walletRepository.findByCentreId(centre.getId())
                .orElseThrow(() -> new IllegalStateException("That centre has no wallet"));

        BigDecimal before = ledgerRepository.deriveBalance(wallet.getId());
        if (before.compareTo(charge) < 0) {
            throw new InsufficientWalletException(
                    "%s has NGN %s available and this booking costs NGN %s. "
                            .formatted(centre.getName(), before, charge)
                            + "Finance must credit the wallet before it can be approved.");
        }

        BigDecimal after = before.subtract(charge);

        WalletTransaction entry = new WalletTransaction();
        entry.setCentre(centre);
        entry.setWallet(wallet);
        entry.setTransactionReference("DB-" + appointmentReference);
        entry.setDirection(LedgerDirection.DEBIT);
        entry.setStatus(LedgerEntryStatus.POSTED);
        entry.setAmount(charge);
        entry.setBalanceBefore(before);
        entry.setBalanceAfter(after);
        entry.setDescription("Approved centre booking " + appointmentReference);
        entry.setSource("CENTRE_BOOKING");
        WalletTransaction saved = ledgerRepository.save(entry);

        wallet.setBalance(after);
        wallet.setLastDebitedAt(LocalDateTime.now());
        walletRepository.save(wallet);

        raiseAlertsIfNeeded(wallet, after);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.WALLET_DEBITED)
                .entityType("Wallet")
                .entityId(wallet.getId())
                .details("NGN %s for booking %s, balance now %s"
                        .formatted(charge, appointmentReference, after))
                .build());

        return saved;
    }

    /**
     * Raises a warning once per threshold crossing.
     *
     * Firing on every booking below the threshold would produce dozens of
     * identical warnings in a morning and train Finance to ignore them, which
     * is worse than not alerting.
     */
    private void raiseAlertsIfNeeded(Wallet wallet, BigDecimal balance) {
        BigDecimal charge = configuration.getDecimal(ConfigurationKeys.CENTRE_BOOKING_CHARGE_NGN);
        // Thresholds are a percentage of a notional full wallet. Expressed in
        // bookings, which is the unit a coordinator and Finance both think in:
        // "about four consultations left" is more useful than a naira figure.
        BigDecimal fullWallet = charge.multiply(BigDecimal.valueOf(100));

        int warnPercent = configuration.getInt(ConfigurationKeys.WALLET_WARNING_PERCENT);
        int criticalPercent = configuration.getInt(ConfigurationKeys.WALLET_CRITICAL_PERCENT);

        BigDecimal warnAt = fullWallet.multiply(BigDecimal.valueOf(warnPercent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal criticalAt = fullWallet.multiply(BigDecimal.valueOf(criticalPercent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        if (balance.compareTo(criticalAt) <= 0) {
            raiseOnce(wallet, "CRITICAL", balance, criticalAt);
        } else if (balance.compareTo(warnAt) <= 0) {
            raiseOnce(wallet, "WARNING", balance, warnAt);
        }
    }

    private void raiseOnce(Wallet wallet, String level, BigDecimal balance, BigDecimal threshold) {
        if (alertRepository.findOpen(wallet.getCentre().getId(), level).isPresent()) {
            return;
        }
        WalletAlert alert = new WalletAlert();
        alert.setCentre(wallet.getCentre());
        alert.setWallet(wallet);
        alert.setAlertLevel(level);
        alert.setBalanceAtAlert(balance);
        alert.setThresholdAmount(threshold);
        alert.setRaisedAt(LocalDateTime.now());
        alertRepository.save(alert);

        BigDecimal charge = configuration.getDecimal(ConfigurationKeys.CENTRE_BOOKING_CHARGE_NGN);
        long bookingsLeft = balance.divide(charge, 0, RoundingMode.DOWN).longValue();

        // To Finance, never to the centre. Centres do not see amounts.
        notifications.notifyRole("FINANCE", null, NotificationType.CENTRE_WALLET_ALERT,
                "%s wallet balance is %s".formatted(wallet.getCentre().getName(),
                        "CRITICAL".equals(level) ? "critically low" : "low"),
                "Balance NGN %s, about %d booking(s) remaining."
                        .formatted(balance, bookingsLeft),
                "/finance/wallets", "Wallet", wallet.getId());

        log.warn("{} wallet {} alert: balance {}, ~{} bookings left",
                wallet.getCentre().getCode(), level, balance, bookingsLeft);
    }

    private void clearAlertsAbove(Wallet wallet, BigDecimal balance) {
        alertRepository.findAllByClearedAtIsNullOrderByRaisedAtAsc().stream()
                .filter(a -> a.getCentre().getId().equals(wallet.getCentre().getId()))
                .filter(a -> balance.compareTo(a.getThresholdAmount()) > 0)
                .forEach(a -> {
                    a.setClearedAt(LocalDateTime.now());
                    alertRepository.save(a);
                });
    }

    public static class InsufficientWalletException extends RuntimeException {
        public InsufficientWalletException(String message) {
            super(message);
        }
    }
}
