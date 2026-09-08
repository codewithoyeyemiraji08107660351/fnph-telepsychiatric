package com.fnph.telepsychiatric.payment;

import com.fnph.telepsychiatric.patient.Patient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The patient credit ledger.
 *
 * Payment is non-refundable. A patient nonetheless pays before the Hub
 * Coordinator approves, so a rejection would leave them out of pocket for a
 * decision they had no part in, over a consultation that never happened. The
 * money stays with FNPH and becomes a credit against their next booking.
 *
 * Append-only. Balance is derived from the entries, never read from a mutable
 * field, for the same reason as the centre wallet: a cached balance and a
 * ledger are two things that can disagree, and only one of them is evidence.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PatientCreditService {

    private final PatientCreditRepository creditRepository;

    @Transactional(readOnly = true)
    public BigDecimal balanceFor(Long patientId) {
        BigDecimal balance = creditRepository.deriveBalance(patientId, LocalDateTime.now());
        return balance == null ? BigDecimal.ZERO : balance;
    }

    @Transactional(readOnly = true)
    public List<PatientCredit> historyFor(Long patientId) {
        return creditRepository.findAllByPatientIdOrderByIdDesc(patientId);
    }

    /**
     * Issues credit for a payment that will not be used.
     *
     * Called when the Hub Coordinator rejects a booking, when the hospital
     * cancels, and when Remita reports an overpayment.
     */
    @Transactional
    public PatientCredit issue(Patient patient, BigDecimal amount, String reason,
                               Long sourcePaymentId, LocalDateTime expiresAt) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("A credit must be a positive amount");
        }

        BigDecimal balance = balanceFor(patient.getId()).add(amount);

        PatientCredit entry = new PatientCredit();
        entry.setPatient(patient);
        entry.setDirection(LedgerDirection.CREDIT);
        entry.setAmount(amount);
        entry.setBalanceAfter(balance);
        entry.setReason(reason);
        entry.setSourcePaymentId(sourcePaymentId);
        entry.setExpiresAt(expiresAt);

        PatientCredit saved = creditRepository.save(entry);
        log.info("Credited {} to patient {}: {}", amount, patient.getPublicId(), reason);
        return saved;
    }

    /**
     * Consumes credit against a new payment.
     *
     * @return how much was actually applied, which is the lesser of the balance
     *         and the amount owed. A partial application is normal: a credit
     *         from a cancelled booking may not cover a fee that has since
     *         changed.
     */
    @Transactional
    public BigDecimal apply(Patient patient, BigDecimal amountOwed, Long paymentId) {
        BigDecimal available = balanceFor(patient.getId());
        if (available.signum() <= 0 || amountOwed.signum() <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal applied = available.min(amountOwed);

        PatientCredit entry = new PatientCredit();
        entry.setPatient(patient);
        entry.setDirection(LedgerDirection.DEBIT);
        entry.setAmount(applied);
        entry.setBalanceAfter(available.subtract(applied));
        entry.setReason("Applied to payment " + paymentId);
        entry.setAppliedPaymentId(paymentId);
        creditRepository.save(entry);

        log.info("Applied {} credit for patient {} to payment {}",
                applied, patient.getPublicId(), paymentId);
        return applied;
    }
}
