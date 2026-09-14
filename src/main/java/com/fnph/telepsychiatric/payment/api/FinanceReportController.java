package com.fnph.telepsychiatric.payment.api;

import com.fnph.telepsychiatric.center.CenterRepository;
import com.fnph.telepsychiatric.centre.CentreWalletService;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.patient.PatientRepository;
import com.fnph.telepsychiatric.payment.*;
import com.fnph.telepsychiatric.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/finance")
@RequiredArgsConstructor
@Tag(name = "Finance — Reporting")
public class FinanceReportController {

    private final PaymentRepository paymentRepository;
    private final CenterRepository centreRepository;
    private final CentreWalletService walletService;
    private final PatientCreditService creditService;
    private final ReconciliationExceptionRepository exceptionRepository;
    private final FinanceReportService reportService;
    private final PatientRepository patientRepository;


    @GetMapping("/report")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).FINANCE_REPORT_READ)")
    @Operation(
            summary = "Financial summary for a period",
            description = """
                    Collected, credited, and what is outstanding.

                    **`heldAsCredit` is the number to watch.** Payment is non-refundable, so
                    every rejected or cancelled booking leaves money on a patient's account.
                    A figure that keeps growing means bookings are being refused faster than
                    patients are rebooking, which is an operational problem rather than a
                    financial one, and Finance is where it shows up first.

                    `unresolvedExceptions` above zero means a discrepancy is waiting on a
                    decision. Those never resolve themselves.

                    **Requires** `finance_report.read`, held by Finance and the Central
                    Administrator.
                    """)
    @ApiResponse(responseCode = "200", description = "Summary returned.")
    public ResponseEntity<Map<String, Object>> report(
            @Parameter(description = "From. Defaults to the last 30 days.")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {

        LocalDateTime start = from == null ? LocalDateTime.now().minusDays(30) : from;
        LocalDateTime end = to == null ? LocalDateTime.now() : to;

        List<Payment> payments = paymentRepository
                .search(start, end, null, PageRequest.of(0, 10_000)).getContent();

        BigDecimal collected = payments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCESS)
                .map(p -> p.getAmount().subtract(p.getCreditApplied()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal creditApplied = payments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCESS)
                .map(Payment::getCreditApplied)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long pending = payments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.PENDING).count();
        long failed = payments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.FAILED).count();
        long mismatched = payments.stream()
                .filter(p -> Boolean.TRUE.equals(p.getAmountMismatch())).count();

        BigDecimal walletTotal = centreRepository.findAll().stream()
                .map(c -> walletService.balanceOf(c.getId()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("periodStart", start);
        body.put("periodEnd", end);
        body.put("currency", "NGN");
        body.put("collectedFromProvider", collected);
        body.put("settledFromPatientCredit", creditApplied);
        body.put("paymentsPending", pending);
        body.put("paymentsFailed", failed);
        body.put("amountMismatches", mismatched);
        body.put("unresolvedExceptions", exceptionRepository.countByResolvedAtIsNull());
        body.put("centreWalletBalanceTotal", walletTotal);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/payments/{reference}/refund")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PAYMENT_REFUND)")
    @Operation(
            summary = "Record a refund made outside the system",
            description = """
                    **This does not move money.** Payment is non-refundable by FNPH's
                    decision, and there is no call to the provider behind this endpoint.

                    It exists for the exception the decision does not cover: a duplicate
                    charge, or a payment taken against the wrong account. Somebody refunds
                    that through the bank or through Remita directly, and this records that
                    it happened so the ledger and the money agree.

                    The patient's credit balance is reduced by the same amount, because the
                    money has left FNPH and leaving the credit in place would let them spend
                    it twice.

                    A reason is required and appears in the audit trail. A refund on a
                    non-refundable service is a decision somebody will have to account for.

                    **Requires** `payment.refund`, held by Finance.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Refund recorded."),
            @ApiResponse(responseCode = "400",
                    description = "The payment was never successful, or no reason was given.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @Transactional
    public ResponseEntity<Map<String, Object>> recordRefund(
            @PathVariable String reference,
            @Parameter(description = "How it was refunded and why.", required = true)
            @RequestParam String reason,
            @Parameter(description = "The bank or provider reference for the refund.")
            @RequestParam(required = false) String externalReference) {

        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Say why this was refunded and how. Payment is non-refundable by "
                            + "decision, so a refund is an exception somebody will have to "
                            + "account for.");
        }

        Payment payment = paymentRepository.findByReference(reference)
                .orElseThrow(() -> new EntityNotFoundException("No payment with that reference"));

        if (payment.getStatus() != PaymentStatus.SUCCESS) {
            throw new IllegalArgumentException(
                    "Only a successful payment can be refunded. This one is "
                            + payment.getStatus() + ".");
        }

        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setReconciliationStatus("REFUNDED_EXTERNALLY");
        payment.setFailureReason("Refunded outside the system: " + reason
                + (externalReference == null ? "" : " (" + externalReference + ")"));
        paymentRepository.save(payment);

        // The money has left FNPH. Leaving the credit in place would let the
        // patient spend it a second time.
        BigDecimal balance = creditService.balanceFor(payment.getPatient().getId());
        BigDecimal toRemove = balance.min(payment.getAmount());
        if (toRemove.signum() > 0) {
            creditService.apply(payment.getPatient(), toRemove, payment.getId());
        }

        return ResponseEntity.ok(Map.of(
                "reference", payment.getReference(),
                "status", payment.getStatus().name(),
                "creditReduced", toRemove,
                "recordedBy", CurrentUser.usernameOrSystem()));
    }

    // -----------------------------------------------------------------
    // The seven reports
    //
    // Module 1 names daily, monthly, reconciliation, exception, failure,
    // reversal and patient-reference. The /report endpoint above stays: it is
    // the dashboard figure, and these are the detail behind it.
    //
    // Every response carries its period, filters, generation time and
    // freshness, because a figure printed and taken to a meeting is worthless
    // without them. Two documents disagreeing is either the money moving or
    // the reports being run an hour apart, and the envelope is what tells you
    // which.
    // -----------------------------------------------------------------

    @GetMapping("/reports/daily")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).FINANCE_REPORT_READ)")
    @Operation(
            summary = "Daily collection report",
            description = """
                    One row per day with activity: payments attempted, payments successful,
                    collected from the provider and settled from patient credit.

                    The two money columns are separate on purpose. A day where most
                    consultations were settled from credit collected almost nothing new,
                    and a single total would hide that.

                    Defaults to the last 30 days.

                    **Requires** `finance_report.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Report returned.")
    public ResponseEntity<FinanceReportService.Report> daily(
            @Parameter(description = "From. Defaults to 30 days ago.")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ResponseEntity.ok(reportService.daily(
                from == null ? LocalDateTime.now().minusDays(30) : from,
                to == null ? LocalDateTime.now() : to));
    }

    @GetMapping("/reports/monthly")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).FINANCE_REPORT_READ)")
    @Operation(
            summary = "Monthly collection report",
            description = """
                    Successful payments by calendar month for one year.

                    Only successful ones. A monthly figure including attempts is not a
                    revenue figure, and this is the report that gets quoted.

                    Defaults to the current year.

                    **Requires** `finance_report.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Report returned.")
    public ResponseEntity<FinanceReportService.Report> monthly(
            @Parameter(description = "Calendar year.", example = "2026")
            @RequestParam(required = false) Integer year) {
        return ResponseEntity.ok(reportService.monthly(
                year == null ? LocalDateTime.now().getYear() : year));
    }

    @GetMapping("/reports/reconciliation")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).FINANCE_REPORT_READ)")
    @Operation(
            summary = "Reconciliation run history",
            description = """
                    The last 20 runs with what each one checked, matched and flagged.

                    `transactionsChecked` against `matchedCount` is the number to read: a
                    widening gap means the provider and this system are drifting apart, and
                    the exceptions report says how.

                    A run with no `completedAt` did not finish.

                    **Requires** `finance_report.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Report returned.")
    public ResponseEntity<FinanceReportService.Report> reconciliation() {
        return ResponseEntity.ok(reportService.reconciliation());
    }

    @GetMapping("/reports/exceptions")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).FINANCE_REPORT_READ)")
    @Operation(
            summary = "Unresolved reconciliation exceptions",
            description = """
                    Oldest first, deliberately.

                    These never resolve themselves, and the one waiting longest is the one
                    somebody has stopped seeing. A queue ordered newest first hides exactly
                    the item that needs a decision.

                    Each row carries the expected and reported amounts, so the size of the
                    discrepancy is visible without opening it.

                    **Requires** `finance_report.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Report returned.")
    public ResponseEntity<FinanceReportService.Report> exceptions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(reportService.exceptions(page, Math.min(size, 200)));
    }

    @GetMapping("/reports/failures")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).FINANCE_REPORT_READ)")
    @Operation(
            summary = "Failed payment report",
            description ="""
                    Payments that failed at the provider, with the failure reason.

                    A failed payment blocks appointment selection, so a patient in this
                    list tried to book and could not. Read alongside the pending count on
                    the summary: a cluster of failures in one window is usually the
                    provider rather than the patients.

                    Defaults to the last 30 days.

                    **Requires** `finance_report.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Report returned.")
    public ResponseEntity<FinanceReportService.Report> failures(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ResponseEntity.ok(reportService.failures(
                from == null ? LocalDateTime.now().minusDays(30) : from,
                to == null ? LocalDateTime.now() : to));
    }

    @GetMapping("/reports/reversals")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).FINANCE_REPORT_READ)")
    @Operation(
            summary = "Reversal and refund report",
            description = """
                    Reversals at the provider and refunds recorded here, on one page.

                    Both, because the service is non-refundable and either state means
                    money left FNPH after a payment was taken. Every row on this report is
                    a decision somebody has to account for, which is why the refund
                    endpoint requires a reason.

                    `refundReference` is how it was returned; a reversal has none because
                    the provider did it.

                    Defaults to the last 30 days.

                    **Requires** `finance_report.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Report returned.")
    public ResponseEntity<FinanceReportService.Report> reversals(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ResponseEntity.ok(reportService.reversals(
                from == null ? LocalDateTime.now().minusDays(30) : from,
                to == null ? LocalDateTime.now() : to));
    }

    @GetMapping("/reports/patient/{patientPublicId}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).FINANCE_REPORT_READ)")
    @Operation(
            summary = "Patient reference report",
            description = """
                    Every payment for one patient, newest first.

                    What a support call needs: a patient says they paid, and this is the
                    reference, the RRR, the status and the date to read back to them.

                    **Keyed by the patient's public identifier, not the EHR number.** An
                    endpoint that took the EHR number could be walked to discover which
                    numbers exist, and an EHR number is the one thing a caller might guess.

                    No clinical content. Finance has no clinical access.

                    **Requires** `finance_report.read`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Report returned."),
            @ApiResponse(responseCode = "404", description = "No such patient.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<FinanceReportService.Report> byPatient(
            @PathVariable String patientPublicId) {
        var patient = patientRepository.findByPublicId(patientPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such patient"));
        return ResponseEntity.ok(reportService.byPatient(patient.getId()));
    }
}
