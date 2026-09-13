package com.fnph.telepsychiatric.payment.api;

import com.fnph.telepsychiatric.center.CenterRepository;
import com.fnph.telepsychiatric.centre.CentreWalletService;
import com.fnph.telepsychiatric.handler.ErrorResponse;
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
}
