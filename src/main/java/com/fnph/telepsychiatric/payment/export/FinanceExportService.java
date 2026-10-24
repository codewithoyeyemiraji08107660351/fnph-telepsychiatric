package com.fnph.telepsychiatric.payment.export;

import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.center.CenterRepository;
import com.fnph.telepsychiatric.centre.CentreWalletService;
import com.fnph.telepsychiatric.common.HospitalClock;
import com.fnph.telepsychiatric.payment.LedgerDirection;
import com.fnph.telepsychiatric.payment.LedgerEntryStatus;
import com.fnph.telepsychiatric.payment.Payment;
import com.fnph.telepsychiatric.payment.PaymentPurpose;
import com.fnph.telepsychiatric.payment.PaymentRepository;
import com.fnph.telepsychiatric.payment.PaymentStatus;
import com.fnph.telepsychiatric.payment.ReconciliationException;
import com.fnph.telepsychiatric.payment.ReconciliationExceptionRepository;
import com.fnph.telepsychiatric.payment.Wallet;
import com.fnph.telepsychiatric.payment.WalletRepository;
import com.fnph.telepsychiatric.payment.WalletTransaction;
import com.fnph.telepsychiatric.payment.WalletTransactionRepository;
import com.fnph.telepsychiatric.payment.export.FinanceWorkbook.Column;
import com.fnph.telepsychiatric.payment.export.FinanceWorkbook.SummaryLine;
import com.fnph.telepsychiatric.payment.export.FinanceWorkbook.Table;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.function.Function;

/**
 * Builds the Finance Excel export for a chosen period.
 *
 * <h2>The period is in hospital days</h2>
 *
 * Finance picks dates as they are lived in Kaduna. "1 to 30 September" means
 * 00:00 WAT on the 1st up to, not including, 00:00 WAT on the 1st of October.
 * The window is converted to UTC once, here, and every timestamp in the file is
 * converted back to WAT, so a payment made at 00:30 on the 1st lands on the 1st
 * rather than on the last day of August.
 *
 * <h2>Refused, never cut short</h2>
 *
 * A period with more payments than one export holds is refused with the count,
 * so Finance narrows it. A file that silently stopped at row 10,000 would total
 * to the wrong figure and nothing on it would say so.
 *
 * <h2>Totals agree with the screens</h2>
 *
 * "Collected via Remita" is the charged amount less patient credit applied, on
 * successful payments only, which is the rule the Finance summary already uses.
 * The summary sheet and the total rows of the Payments and Daily sheets are
 * computed from the same rows, so they cannot disagree with each other.
 */
@Service
@RequiredArgsConstructor
public class FinanceExportService {

    public static final int MAX_DAYS = 366;
    public static final int MAX_ROWS = 10_000;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH);
    /** Summary order: the outcome Finance looks for first, then what needs chasing. */
    static final List<PaymentStatus> STATUS_ORDER = List.of(
            PaymentStatus.SUCCESS, PaymentStatus.PENDING, PaymentStatus.FAILED,
            PaymentStatus.REVERSED, PaymentStatus.REFUNDED, PaymentStatus.UNMATCHED);
    private static final Set<PaymentStatus> REVERSED_OR_REFUNDED =
            EnumSet.of(PaymentStatus.REVERSED, PaymentStatus.REFUNDED);

    private final PaymentRepository paymentRepository;
    private final ReconciliationExceptionRepository exceptionRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final WalletRepository walletRepository;
    private final CenterRepository centreRepository;
    private final CentreWalletService walletService;

    /**
     * @param from        first day, WAT, inclusive
     * @param to          last day, WAT, inclusive. A day after today is read as
     *                    today: the future has no payments, and a "this month"
     *                    export run mid-month should say what it actually covers
     * @param generatedBy username printed on every sheet
     */
    @Transactional(readOnly = true)
    public Export export(LocalDate from, LocalDate to, String generatedBy) {
        LocalDate today = HospitalClock.today();
        validate(from, to, today);
        LocalDate lastDay = to.isAfter(today) ? today : to;

        LocalDateTime start = HospitalClock.toUtc(from, LocalTime.MIDNIGHT);
        LocalDateTime end = HospitalClock.toUtc(lastDay.plusDays(1), LocalTime.MIDNIGHT);

        long paymentCount = paymentRepository.countStartedBetween(start, end);
        if (paymentCount > MAX_ROWS) {
            throw new IllegalArgumentException(("%,d payments were started in this period, more than "
                    + "the %,d one export holds. Choose a shorter period.").formatted(paymentCount, MAX_ROWS));
        }

        List<Payment> payments = paymentRepository.findStartedBetween(start, end, PageRequest.of(0, MAX_ROWS));
        List<ReconciliationException> exceptions =
                exceptionRepository.findRaisedBetween(start, end, PageRequest.of(0, MAX_ROWS));
        List<WalletTransaction> ledger =
                walletTransactionRepository.findAllBetweenForExport(start, end, PageRequest.of(0, MAX_ROWS + 1));
        boolean ledgerTruncated = ledger.size() > MAX_ROWS;
        if (ledgerTruncated) {
            ledger = ledger.subList(0, MAX_ROWS);
        }
        List<CentreWallet> wallets = centreWallets(start, end, ledger, ledgerTruncated);

        LocalDateTime generatedAt = LocalDateTime.now(HospitalClock.ZONE);
        List<String> provenance = List.of(
                "Period: %s to %s, West Africa Time".formatted(DAY.format(from), DAY.format(lastDay)),
                "Generated: %s WAT by %s".formatted(STAMP.format(generatedAt), generatedBy),
                "Source: live system records. Payments are dated by when they were started.");

        FinanceWorkbook book = new FinanceWorkbook(
                "FNPH Kaduna Telepsychiatry: Finance report",
                provenance,
                summary(payments, exceptions, wallets, ledgerTruncated),
                List.of(
                        paymentsTable(payments),
                        dailyTable(payments, from, lastDay),
                        failedAndReversedTable(payments),
                        exceptionsTable(exceptions),
                        walletsTable(wallets),
                        ledgerTable(ledger, ledgerTruncated)));

        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        try {
            FinanceWorkbookWriter.write(book, out);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the finance workbook", e);
        }

        String filename = "FNPH-finance-report_%s_to_%s.xlsx".formatted(from, lastDay);
        return new Export(filename, out.toByteArray(), from, lastDay, payments.size());
    }

    static void validate(LocalDate from, LocalDate to, LocalDate today) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("Choose a start date and an end date.");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("The end date is before the start date.");
        }
        if (from.isAfter(today)) {
            throw new IllegalArgumentException("The start date is in the future. There is nothing to report yet.");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new IllegalArgumentException(
                    "One export covers at most %d days. Choose a shorter period.".formatted(MAX_DAYS));
        }
    }

    // ----------------------------------------------------------------- summary

    private List<SummaryLine> summary(List<Payment> payments, List<ReconciliationException> exceptions,
                                      List<CentreWallet> wallets, boolean ledgerTruncated) {
        List<Payment> successful = payments.stream().filter(p -> p.getStatus() == PaymentStatus.SUCCESS).toList();
        List<SummaryLine> lines = new ArrayList<>();

        lines.add(SummaryLine.heading("Collections"));
        lines.add(SummaryLine.money("Collected via Remita (NGN)", sum(successful, FinanceExportService::collected),
                "Successful payments, less any patient credit applied. Matches the Finance overview."));
        lines.add(SummaryLine.money("  of which patient consultations (NGN)",
                sum(successful, p -> p.getPurpose() == PaymentPurpose.PATIENT_CONSULTATION ? collected(p) : null),
                null));
        lines.add(SummaryLine.money("  of which centre wallet funding (NGN)",
                sum(successful, p -> p.getPurpose() == PaymentPurpose.CENTRE_WALLET_FUNDING ? collected(p) : null),
                null));
        lines.add(SummaryLine.money("Settled from patient credit (NGN)", sum(successful, FinanceExportService::creditSettled),
                "Bookings paid from credit held on the patient's account. No new money."));
        lines.add(SummaryLine.money("Total value of successful payments (NGN)", sum(successful, Payment::getAmount),
                "Collected via Remita plus settled from credit."));

        lines.add(SummaryLine.heading("Payments started in the period"));
        lines.add(SummaryLine.count("All payments", (long) payments.size(), null));
        for (PaymentStatus status : STATUS_ORDER) {
            lines.add(SummaryLine.count(statusLabel(status),
                    payments.stream().filter(p -> p.getStatus() == status).count(),
                    status == PaymentStatus.PENDING
                            ? "Not yet confirmed by Remita. Re-export later for a final figure." : null));
        }
        lines.add(SummaryLine.money("Value reversed or refunded (NGN)",
                sum(payments, p -> REVERSED_OR_REFUNDED.contains(p.getStatus()) ? p.getAmount() : null),
                "Money that left FNPH after a payment was taken. Listed on the Failed and reversed sheet."));
        lines.add(SummaryLine.count("Amount mismatches",
                payments.stream().filter(p -> Boolean.TRUE.equals(p.getAmountMismatch())).count(),
                "Remita reported a different amount from the one charged."));

        lines.add(SummaryLine.heading("Reconciliation"));
        lines.add(SummaryLine.count("Exceptions raised in the period", (long) exceptions.size(), null));
        lines.add(SummaryLine.count("  of which still open",
                exceptions.stream().filter(e -> e.getResolvedAt() == null).count(), null));
        lines.add(SummaryLine.count("All open exceptions today", exceptionRepository.countByResolvedAtIsNull(),
                "Across every period. These never resolve themselves."));

        lines.add(SummaryLine.heading("Centre wallets"));
        lines.add(SummaryLine.money("Opening balance, all centres (NGN)",
                wallets.stream().map(CentreWallet::opening).reduce(BigDecimal.ZERO, BigDecimal::add), null));
        lines.add(SummaryLine.money("Credited in the period (NGN)",
                wallets.stream().map(CentreWallet::credits).reduce(BigDecimal.ZERO, BigDecimal::add), null));
        lines.add(SummaryLine.money("Debited in the period (NGN)",
                wallets.stream().map(CentreWallet::debits).reduce(BigDecimal.ZERO, BigDecimal::add), null));
        lines.add(SummaryLine.money("Closing balance, all centres (NGN)",
                wallets.stream().map(CentreWallet::closing).reduce(BigDecimal.ZERO, BigDecimal::add),
                "From the ledger at the end of the period."));
        lines.add(SummaryLine.money("Balance now, all centres (NGN)",
                wallets.stream().map(CentreWallet::now).reduce(BigDecimal.ZERO, BigDecimal::add),
                "At the time this file was generated."));
        if (ledgerTruncated) {
            lines.add(SummaryLine.text("Wallet ledger", "Incomplete",
                    "More than %,d ledger entries in this period. The ledger sheet and the credit and debit "
                            .formatted(MAX_ROWS)
                            + "totals cover the first %,d only. Export a shorter period.".formatted(MAX_ROWS)));
        }
        return lines;
    }

    // ----------------------------------------------------------------- sheets

    private Table paymentsTable(List<Payment> payments) {
        List<Column> columns = List.of(
                Column.dateTime("Started (WAT)"),
                Column.text("Reference", 24),
                Column.text("Remita RRR", 16),
                Column.text("EHR number", 14),
                Column.text("Purpose", 22),
                Column.text("Status", 12),
                Column.money("Charged (NGN)"),
                Column.money("Collected via Remita (NGN)").totalled(),
                Column.money("Settled from patient credit (NGN)").totalled(),
                Column.money("Reported by Remita (NGN)"),
                Column.text("Amount mismatch", 11),
                Column.text("Channel", 14),
                Column.dateTime("Paid on (WAT)"),
                Column.dateTime("Verified (WAT)"),
                Column.text("Failure reason", 36),
                Column.dateTime("Refunded (WAT)"),
                Column.text("Refund reference", 20),
                Column.text("Reconciliation", 16));

        List<List<Object>> rows = payments.stream().map(p -> row(
                wat(p.getCreatedAt()),
                p.getReference(),
                p.getRrr(),
                p.getPatient() == null ? null : p.getPatient().getEhrNumber(),
                label(p.getPurpose().name()),
                statusLabel(p.getStatus()),
                p.getAmount(),
                orZero(collected(p)),
                orZero(creditSettled(p)),
                p.getReportedAmount(),
                Boolean.TRUE.equals(p.getAmountMismatch()),
                p.getPaymentChannel(),
                wat(p.getPaymentDate()),
                wat(p.getVerifiedAt()),
                p.getFailureReason(),
                wat(p.getRefundedAt()),
                p.getRefundReference(),
                p.getReconciliationStatus() == null ? null : label(p.getReconciliationStatus())
        )).toList();

        return new Table("Payments", "Payments",
                "Every payment started in the period, oldest first. Patients are identified by EHR number only.",
                columns, rows, "No payments were started in this period.");
    }

    private Table dailyTable(List<Payment> payments, LocalDate from, LocalDate lastDay) {
        Map<LocalDate, long[]> counts = new LinkedHashMap<>();
        Map<LocalDate, BigDecimal[]> money = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(lastDay); d = d.plusDays(1)) {
            counts.put(d, new long[5]);
            money.put(d, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        }
        for (Payment p : payments) {
            LocalDateTime started = wat(p.getCreatedAt());
            if (started == null || !counts.containsKey(started.toLocalDate())) {
                continue;
            }
            long[] c = counts.get(started.toLocalDate());
            c[0]++;
            switch (p.getStatus()) {
                case SUCCESS -> c[1]++;
                case PENDING -> c[2]++;
                case FAILED -> c[3]++;
                case REVERSED, REFUNDED -> c[4]++;
                default -> { }
            }
            if (p.getStatus() == PaymentStatus.SUCCESS) {
                BigDecimal[] m = money.get(started.toLocalDate());
                m[0] = m[0].add(orZero(collected(p)));
                m[1] = m[1].add(orZero(creditSettled(p)));
            }
        }

        List<Column> columns = List.of(
                Column.date("Date (WAT)"),
                Column.count("Started").totalled(),
                Column.count("Successful").totalled(),
                Column.count("Pending").totalled(),
                Column.count("Failed").totalled(),
                Column.count("Reversed or refunded").totalled(),
                Column.money("Collected via Remita (NGN)").totalled(),
                Column.money("Settled from patient credit (NGN)").totalled());

        List<List<Object>> rows = new ArrayList<>();
        counts.forEach((day, c) -> {
            BigDecimal[] m = money.get(day);
            rows.add(row(day, c[0], c[1], c[2], c[3], c[4], m[0], m[1]));
        });
        return new Table("Daily totals", "Daily totals",
                "One row per day in the period, including days with no activity.",
                columns, rows, "No days in this period.");
    }

    private Table failedAndReversedTable(List<Payment> payments) {
        List<Column> columns = List.of(
                Column.dateTime("Started (WAT)"),
                Column.text("Reference", 24),
                Column.text("Remita RRR", 16),
                Column.text("EHR number", 14),
                Column.text("Purpose", 22),
                Column.text("Status", 12),
                Column.money("Charged (NGN)"),
                Column.text("Failure reason", 44),
                Column.dateTime("Refunded (WAT)"),
                Column.text("Refund reference", 20));

        List<List<Object>> rows = payments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.FAILED || REVERSED_OR_REFUNDED.contains(p.getStatus()))
                .map(p -> row(
                        wat(p.getCreatedAt()),
                        p.getReference(),
                        p.getRrr(),
                        p.getPatient() == null ? null : p.getPatient().getEhrNumber(),
                        label(p.getPurpose().name()),
                        statusLabel(p.getStatus()),
                        p.getAmount(),
                        p.getFailureReason(),
                        wat(p.getRefundedAt()),
                        p.getRefundReference()))
                .toList();

        return new Table("Failed and reversed", "Failed, reversed and refunded payments",
                "Payments in the period that did not end as money held by FNPH.",
                columns, rows, "No failed, reversed or refunded payments in this period.");
    }

    private Table exceptionsTable(List<ReconciliationException> exceptions) {
        List<Column> columns = List.of(
                Column.dateTime("Raised (WAT)"),
                Column.text("Type", 24),
                Column.text("Payment reference", 24),
                Column.money("Expected (NGN)"),
                Column.money("Reported by Remita (NGN)"),
                Column.money("Difference (NGN)"),
                Column.text("Details", 44),
                Column.text("Status", 10),
                Column.dateTime("Resolved (WAT)"),
                Column.text("Resolved by", 16),
                Column.text("Resolution notes", 44));

        List<List<Object>> rows = exceptions.stream().map(e -> row(
                wat(e.getCreatedAt()),
                label(e.getExceptionType().name()),
                e.getPayment() == null ? null : e.getPayment().getReference(),
                e.getExpectedAmount(),
                e.getReportedAmount(),
                e.getExpectedAmount() == null || e.getReportedAmount() == null
                        ? null : e.getReportedAmount().subtract(e.getExpectedAmount()),
                e.getDetails(),
                e.getResolvedAt() == null ? "Open" : "Resolved",
                wat(e.getResolvedAt()),
                e.getResolvedBy(),
                e.getResolutionNotes()
        )).toList();

        return new Table("Exceptions", "Reconciliation exceptions",
                "Raised in the period, resolved or not. Difference is reported less expected.",
                columns, rows, "No reconciliation exceptions were raised in this period.");
    }

    private Table walletsTable(List<CentreWallet> wallets) {
        List<Column> columns = List.of(
                Column.text("Centre", 34),
                Column.text("Code", 12),
                Column.money("Opening balance (NGN)").totalled(),
                Column.money("Credited (NGN)").totalled(),
                Column.money("Debited (NGN)").totalled(),
                Column.money("Closing balance (NGN)").totalled(),
                Column.money("Balance now (NGN)").totalled(),
                Column.count("Ledger entries").totalled());

        List<List<Object>> rows = wallets.stream().map(w -> row(
                w.name(), w.code(), w.opening(), w.credits(), w.debits(), w.closing(), w.now(), w.entries()
        )).toList();

        return new Table("Centre wallets", "Centre wallets",
                "Opening and closing balances come from posted ledger entries. Reversed entries are not counted.",
                columns, rows, "No centres are set up.");
    }

    private Table ledgerTable(List<WalletTransaction> ledger, boolean truncated) {
        List<Column> columns = List.of(
                Column.dateTime("Date (WAT)"),
                Column.text("Centre", 30),
                Column.text("Direction", 10),
                Column.money("Amount (NGN)"),
                Column.money("Balance after (NGN)"),
                Column.text("Reference", 26),
                Column.text("Description", 44),
                Column.text("Source", 18),
                Column.text("Status", 10));

        List<List<Object>> rows = ledger.stream().map(t -> row(
                wat(t.getCreatedAt()),
                t.getCentre().getName(),
                label(t.getDirection().name()),
                t.getAmount(),
                t.getBalanceAfter(),
                t.getTransactionReference(),
                t.getDescription(),
                t.getSource() == null ? null : label(t.getSource()),
                label(t.getStatus().name())
        )).toList();

        String note = truncated
                ? "INCOMPLETE: the first %,d entries only. Export a shorter period for the full ledger.".formatted(MAX_ROWS)
                : "Every centre wallet entry in the period, oldest first.";
        return new Table("Wallet ledger", "Centre wallet ledger", note, columns, rows,
                "No wallet entries in this period.");
    }

    // ----------------------------------------------------------------- wallets

    private List<CentreWallet> centreWallets(LocalDateTime start, LocalDateTime end,
                                             List<WalletTransaction> ledger, boolean ledgerTruncated) {
        List<CentreWallet> result = new ArrayList<>();
        List<Center> centres = centreRepository.findAll().stream()
                .sorted(Comparator.comparing(Center::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();

        for (Center centre : centres) {
            List<WalletTransaction> entries = ledger.stream()
                    .filter(t -> t.getCentre().getId().equals(centre.getId()))
                    .toList();
            BigDecimal credits = postedTotal(entries, LedgerDirection.CREDIT);
            BigDecimal debits = postedTotal(entries, LedgerDirection.DEBIT);

            Wallet wallet = walletRepository.findByCentreId(centre.getId()).orElse(null);
            BigDecimal opening = wallet == null ? BigDecimal.ZERO
                    : walletTransactionRepository.balanceBefore(wallet.getId(), start);
            // With a full ledger the closing balance is read from the ledger.
            // When the ledger was cut short, the credit and debit columns are
            // partial and the closing figure is still the true one, so the row
            // will not add across; the summary sheet says why.
            BigDecimal closing = wallet == null ? BigDecimal.ZERO
                    : walletTransactionRepository.balanceBefore(wallet.getId(), end);

            result.add(new CentreWallet(centre.getName(), centre.getCode(), opening, credits, debits,
                    closing, walletService.balanceOf(centre.getId()), (long) entries.size()));
        }
        return result;
    }

    private static BigDecimal postedTotal(List<WalletTransaction> entries, LedgerDirection direction) {
        return entries.stream()
                .filter(t -> t.getStatus() == LedgerEntryStatus.POSTED && t.getDirection() == direction)
                .map(WalletTransaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ----------------------------------------------------------------- helpers

    /** Money that arrived through Remita for this payment, or null when none did. */
    static BigDecimal collected(Payment p) {
        if (p.getStatus() != PaymentStatus.SUCCESS || p.getAmount() == null) {
            return null;
        }
        return p.getAmount().subtract(orZero(p.getCreditApplied()));
    }

    /** Patient credit that settled this payment, or null when it did not succeed. */
    static BigDecimal creditSettled(Payment p) {
        return p.getStatus() == PaymentStatus.SUCCESS ? orZero(p.getCreditApplied()) : null;
    }

    private static BigDecimal sum(List<Payment> payments, Function<Payment, BigDecimal> value) {
        return payments.stream().map(value).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** A stored UTC timestamp as WAT wall-clock time. */
    static LocalDateTime wat(LocalDateTime utc) {
        return utc == null ? null
                : utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(HospitalClock.ZONE).toLocalDateTime();
    }

    static String statusLabel(PaymentStatus status) {
        return status == PaymentStatus.SUCCESS ? "Successful" : label(status.name());
    }

    /** "CENTRE_WALLET_FUNDING" to "Centre wallet funding". */
    static String label(String code) {
        if (code == null || code.isBlank()) {
            return code;
        }
        String words = code.replace('_', ' ').trim().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static List<Object> row(Object... values) {
        return Arrays.asList(values);
    }

    record CentreWallet(String name, String code, BigDecimal opening, BigDecimal credits,
                        BigDecimal debits, BigDecimal closing, BigDecimal now, Long entries) {
    }

    /**
     * A finished export.
     *
     * @param to the last day actually covered, which is today when the
     *           requested end date was later
     */
    public record Export(String filename, byte[] content, LocalDate from, LocalDate to, int paymentCount) {
    }
}
