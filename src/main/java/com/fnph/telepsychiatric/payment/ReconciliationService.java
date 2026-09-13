package com.fnph.telepsychiatric.payment;

import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.payment.remita.RemitaClient;
import com.fnph.telepsychiatric.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Compares what this system recorded against what Remita says.
 *
 * <h2>Why it has to exist</h2>
 *
 * Callbacks get lost. A patient pays, Remita's notification never arrives, the
 * payment sits PENDING and the patient cannot book. Nobody finds out unless
 * they complain, and the ones who do not complain simply do not come back.
 *
 * This is the sweep that catches them.
 *
 * <h2>Nothing is corrected automatically</h2>
 *
 * A payment that Remita confirms is verified here, because that is the same
 * server-to-server check the callback would have triggered and it is not a
 * judgement. Everything else becomes an exception for Finance: an underpayment
 * is not a rounding issue, an overpayment means the patient is owed a credit,
 * and a payment the provider has no record of is a question rather than an
 * answer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReconciliationService {

    private final PaymentRepository paymentRepository;
    private final ReconciliationRepository runRepository;
    private final ReconciliationExceptionRepository exceptionRepository;
    private final PaymentService paymentService;
    private final RemitaClient remitaClient;
    private final AuditService auditService;

    @Transactional
    public ReconciliationRun run(LocalDateTime from, LocalDateTime to, String runType) {
        ReconciliationRun run = new ReconciliationRun();
        run.setRunType(runType);
        run.setPeriodStart(from);
        run.setPeriodEnd(to);
        run.setStartedAt(LocalDateTime.now());
        run.setRunBy(CurrentUser.usernameOrSystem());
        ReconciliationRun saved = runRepository.save(run);

        List<Payment> payments = paymentRepository
                .search(from, to, null, PageRequest.of(0, 5000)).getContent();

        int checked = 0;
        int matched = 0;
        int exceptions = 0;

        for (Payment payment : payments) {
            checked++;

            if (payment.getRrr() == null) {
                // Settled entirely from the patient's wallet. There is nothing
                // at the provider to compare it against, and that is correct.
                matched++;
                continue;
            }

            RemitaClient.VerificationResult result = remitaClient.verify(payment.getRrr());

            if (!result.reachable()) {
                // Not an exception. The provider was unavailable, which says
                // nothing about the payment, and recording it as a discrepancy
                // would fill Finance's queue with noise every time Remita has a
                // bad afternoon.
                log.warn("Remita unreachable for {} during reconciliation", payment.getReference());
                continue;
            }

            if (result.paid() && payment.getStatus() == PaymentStatus.SUCCESS) {
                BigDecimal expected = payment.getAmount().subtract(payment.getCreditApplied());
                if (result.amount() != null && result.amount().compareTo(expected) != 0) {
                    raise(saved, payment, ReconciliationExceptionType.AMOUNT_MISMATCH,
                            "Remita reports a different amount from the order", expected,
                            result.amount());
                    exceptions++;
                } else {
                    matched++;
                }
                continue;
            }

            if (result.paid() && payment.getStatus() != PaymentStatus.SUCCESS) {
                // The callback was lost. This is the case the sweep exists for:
                // the patient paid and could not book, and nobody knew.
                log.info("Reconciliation found an unrecorded payment: {}", payment.getReference());
                paymentService.verify(payment.getReference());
                matched++;
                continue;
            }

            if (!result.paid() && payment.getStatus() == PaymentStatus.SUCCESS) {
                // Recorded as paid here and not at the provider. The most
                // serious discrepancy there is, because a consultation may
                // already have happened against it.
                raise(saved, payment, ReconciliationExceptionType.MISSING_AT_PROVIDER,
                        "Recorded as successful here, but Remita reports status "
                                + result.statusCode(),
                        payment.getAmount(), result.amount());
                exceptions++;
                continue;
            }

            if (payment.getStatus() == PaymentStatus.PENDING
                    && payment.getInitiatedAt().isBefore(LocalDateTime.now().minusHours(48))) {
                raise(saved, payment, ReconciliationExceptionType.STILL_PENDING,
                        "Initiated more than 48 hours ago and still unpaid at the provider",
                        payment.getAmount(), null);
                exceptions++;
            }
        }

        saved.setTransactionsChecked(checked);
        saved.setMatchedCount(matched);
        saved.setExceptionCount(exceptions);
        saved.setCompletedAt(LocalDateTime.now());
        runRepository.save(saved);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.PAYMENT_RECONCILED)
                .entityType("ReconciliationRun")
                .entityId(saved.getId())
                .details("%d checked, %d matched, %d exception(s)"
                        .formatted(checked, matched, exceptions))
                .build());

        log.info("Reconciliation {}: {} checked, {} matched, {} exceptions",
                saved.getPublicId(), checked, matched, exceptions);
        return saved;
    }

    private void raise(ReconciliationRun run, Payment payment,
                       ReconciliationExceptionType type, String details,
                       BigDecimal expected, BigDecimal reported) {
        ReconciliationException exception = new ReconciliationException();
        exception.setRun(run);
        exception.setPayment(payment);
        exception.setExceptionType(type);
        exception.setDetails(details);
        exception.setExpectedAmount(expected);
        exception.setReportedAmount(reported);
        exceptionRepository.save(exception);

        log.error("Reconciliation exception on {}: {} ({})",
                payment.getReference(), type, details);
    }

    @Transactional(readOnly = true)
    public List<ReconciliationRun> recentRuns() {
        return runRepository.findTop20ByOrderByStartedAtDesc();
    }
}
