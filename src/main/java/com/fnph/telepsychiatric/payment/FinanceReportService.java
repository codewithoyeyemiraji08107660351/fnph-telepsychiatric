package com.fnph.telepsychiatric.payment;

import com.fnph.telepsychiatric.center.CenterRepository;
import com.fnph.telepsychiatric.centre.CentreWalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The seven Finance reports Module 1 names.
 *
 * <h2>Every report carries its own provenance</h2>
 *
 * Module 1 requires a report to identify its period, filters, generation time
 * and freshness. A figure printed and taken to a meeting is worthless without
 * them: nobody can tell whether two documents disagree because the money moved
 * or because they were run an hour apart over different windows.
 *
 * <h2>Filtering is server-side</h2>
 *
 * Every query is bounded before it leaves the database. A report that fetches
 * everything and filters in the client is a report that leaks the rows it
 * decided not to show.
 */
@Service
@RequiredArgsConstructor
public class FinanceReportService {

    private static final int MAX_ROWS = 10_000;

    private final PaymentRepository paymentRepository;
    private final ReconciliationRepository reconciliationRepository;
    private final ReconciliationExceptionRepository exceptionRepository;
    private final CenterRepository centreRepository;
    private final CentreWalletService walletService;

    /** 1. Daily totals across the window, one row per day with activity. */
    @Transactional(readOnly = true)
    public Report daily(LocalDateTime from, LocalDateTime to) {
        List<Payment> payments = search(from, to);
        Map<LocalDate, Map<String, Object>> byDay = new TreeMap<>();

        for (Payment p : payments) {
            if (p.getCreatedAt() == null) {
                continue;
            }
            byDay.computeIfAbsent(p.getCreatedAt().toLocalDate(), d -> blankDay())
                    .compute("count", (k, v) -> ((Long) v) + 1);
            if (p.getStatus() == PaymentStatus.SUCCESS) {
                Map<String, Object> row = byDay.get(p.getCreatedAt().toLocalDate());
                row.compute("successCount", (k, v) -> ((Long) v) + 1);
                row.compute("collected", (k, v) -> ((BigDecimal) v)
                        .add(p.getAmount().subtract(p.getCreditApplied())));
                row.compute("settledFromCredit", (k, v) -> ((BigDecimal) v)
                        .add(p.getCreditApplied()));
            }
        }
        byDay.forEach((day, row) -> row.put("date", day));
        return Report.of("daily", from, to, Map.of(), List.copyOf(byDay.values()));
    }

    /** 2. Monthly totals for a calendar year. */
    @Transactional(readOnly = true)
    public Report monthly(int year) {
        LocalDateTime from = LocalDate.of(year, 1, 1).atStartOfDay();
        LocalDateTime to = LocalDate.of(year, 12, 31).atTime(23, 59, 59);
        List<Payment> payments = search(from, to);

        Map<YearMonth, Map<String, Object>> byMonth = new TreeMap<>();
        for (Payment p : payments) {
            if (p.getCreatedAt() == null || p.getStatus() != PaymentStatus.SUCCESS) {
                continue;
            }
            Map<String, Object> row = byMonth.computeIfAbsent(
                    YearMonth.from(p.getCreatedAt()), m -> blankDay());
            row.compute("successCount", (k, v) -> ((Long) v) + 1);
            row.compute("collected", (k, v) -> ((BigDecimal) v)
                    .add(p.getAmount().subtract(p.getCreditApplied())));
            row.compute("settledFromCredit", (k, v) -> ((BigDecimal) v)
                    .add(p.getCreditApplied()));
        }
        byMonth.forEach((month, row) -> row.put("month", month.toString()));
        return Report.of("monthly", from, to, Map.of("year", year),
                List.copyOf(byMonth.values()));
    }

    /** 3. Reconciliation runs, most recent first. */
    @Transactional(readOnly = true)
    public Report reconciliation() {
        List<Map<String, Object>> rows = reconciliationRepository
                .findTop20ByOrderByStartedAtDesc().stream()
                .map(run -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("publicId", run.getPublicId());
                    row.put("runType", run.getRunType());
                    row.put("periodStart", run.getPeriodStart());
                    row.put("periodEnd", run.getPeriodEnd());
                    row.put("startedAt", run.getStartedAt());
                    row.put("completedAt", run.getCompletedAt());
                    row.put("transactionsChecked", run.getTransactionsChecked());
                    row.put("matchedCount", run.getMatchedCount());
                    row.put("exceptionCount", run.getExceptionCount());
                    return row;
                })
                .toList();
        return Report.of("reconciliation", null, null,
                Map.of("limit", 20), rows);
    }

    /**
     * 4. Unresolved reconciliation exceptions, oldest first.
     *
     * Oldest first deliberately. These never resolve themselves, and the one
     * that has been waiting longest is the one somebody has stopped seeing.
     */
    @Transactional(readOnly = true)
    public Report exceptions(int page, int size) {
        var rows = exceptionRepository
                .findAllByResolvedAtIsNullOrderByCreatedAtAsc(PageRequest.of(page, size))
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("publicId", e.getPublicId());
                    row.put("exceptionType", e.getExceptionType().name());
                    row.put("expectedAmount", e.getExpectedAmount());
                    row.put("reportedAmount", e.getReportedAmount());
                    row.put("details", e.getDetails());
                    row.put("raisedAt", e.getCreatedAt());
                    return row;
                })
                .getContent();
        return Report.of("exceptions", null, null,
                Map.of("resolved", false, "page", page, "size", size), rows);
    }

    /** 5. Failed payments in the window. */
    @Transactional(readOnly = true)
    public Report failures(LocalDateTime from, LocalDateTime to) {
        return Report.of("failures", from, to,
                Map.of("status", PaymentStatus.FAILED.name()),
                paymentRows(searchByStatus(from, to, PaymentStatus.FAILED)));
    }

    /**
     * 6. Reversals and recorded refunds.
     *
     * Both, because the service is non-refundable and either state means money
     * left FNPH after a payment was taken. Finance needs them on one page.
     */
    @Transactional(readOnly = true)
    public Report reversals(LocalDateTime from, LocalDateTime to) {
        List<Payment> rows = search(from, to).stream()
                .filter(p -> p.getStatus() == PaymentStatus.REVERSED
                        || p.getStatus() == PaymentStatus.REFUNDED)
                .sorted(Comparator.comparing(Payment::getCreatedAt).reversed())
                .toList();
        return Report.of("reversals", from, to,
                Map.of("statuses", List.of(PaymentStatus.REVERSED, PaymentStatus.REFUNDED)),
                paymentRows(rows));
    }

    /**
     * 7. Every payment for one patient.
     *
     * Keyed by patient id rather than EHR number: the caller resolves the
     * patient through a permission-guarded lookup first, so this cannot be
     * used to probe which EHR numbers exist.
     */
    @Transactional(readOnly = true)
    public Report byPatient(Long patientId) {
        List<Payment> rows = paymentRepository
                .findAllByPatientIdOrderByCreatedAtDesc(patientId);
        return Report.of("patient-reference", null, null,
                Map.of("patientId", patientId), paymentRows(rows));
    }

    // -----------------------------------------------------------------

    private List<Payment> search(LocalDateTime from, LocalDateTime to) {
        return paymentRepository.search(from, to, null,
                PageRequest.of(0, MAX_ROWS)).getContent();
    }

    private List<Payment> searchByStatus(LocalDateTime from, LocalDateTime to,
                                         PaymentStatus status) {
        return paymentRepository.search(from, to, status,
                PageRequest.of(0, MAX_ROWS)).getContent();
    }

    private List<Map<String, Object>> paymentRows(List<Payment> payments) {
        return payments.stream().map(p -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("reference", p.getReference());
            row.put("rrr", p.getRrr());
            row.put("status", p.getStatus().name());
            row.put("amount", p.getAmount());
            row.put("reportedAmount", p.getReportedAmount());
            row.put("amountMismatch", p.getAmountMismatch());
            row.put("creditApplied", p.getCreditApplied());
            row.put("purpose", p.getPurpose().name());
            row.put("createdAt", p.getCreatedAt());
            row.put("verifiedAt", p.getVerifiedAt());
            row.put("failureReason", p.getFailureReason());
            row.put("refundReference", p.getRefundReference());
            return row;
        }).toList();
    }

    private Map<String, Object> blankDay() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("count", 0L);
        row.put("successCount", 0L);
        row.put("collected", BigDecimal.ZERO);
        row.put("settledFromCredit", BigDecimal.ZERO);
        return row;
    }

    /**
     * A report and its provenance.
     *
     * @param freshness how current the data is. "live" here, because every
     *                  query reads the transactional tables directly. It is
     *                  stated rather than assumed so that the day a cache or a
     *                  nightly rollup appears behind one of these, the report
     *                  says so instead of silently going stale.
     */
    public record Report(String name, LocalDateTime periodStart, LocalDateTime periodEnd,
                         Map<String, Object> filters, LocalDateTime generatedAt,
                         String freshness, int rowCount, List<Map<String, Object>> rows) {

        static Report of(String name, LocalDateTime from, LocalDateTime to,
                         Map<String, Object> filters, List<Map<String, Object>> rows) {
            return new Report(name, from, to, filters, LocalDateTime.now(),
                    "live", rows.size(), rows);
        }
    }
}