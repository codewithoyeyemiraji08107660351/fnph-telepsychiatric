package com.fnph.telepsychiatric.payment.api;

import com.fnph.telepsychiatric.center.CenterRepository;
import com.fnph.telepsychiatric.centre.CentreWalletService;
import com.fnph.telepsychiatric.centre.WalletAlert;
import com.fnph.telepsychiatric.centre.WalletAlertRepository;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.payment.*;
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
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/finance")
@RequiredArgsConstructor
@Tag(name = "Finance")
public class FinanceController {

    private final CentreWalletService walletService;
    private final CenterRepository centreRepository;
    private final WalletRepository walletRepository;
    private final WalletTransactionRepository ledgerRepository;
    private final WalletAlertRepository alertRepository;
    private final PaymentRepository paymentRepository;
    private final ReconciliationExceptionRepository exceptionRepository;
    private final ReconciliationService reconciliationService;

    @PostMapping("/wallets/{centrePublicId}/credit")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).WALLET_CREDIT)")
    @Operation(
            summary = "Credit a centre wallet from programme funding",
            description = """
                    Adds funding and clears any low-balance alert the top-up resolves, so
                    Finance is not looking at a warning about a wallet they have just filled.

                    **Centres never see these amounts.** They see consultation and
                    utilisation counts. If a booking is refused for funding, FNPH tells them.

                    The reference should be the funding instrument, so a balance can be
                    traced back to where the money came from.

                    **Requires** `wallet.credit`, held by Finance.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Credited."),
            @ApiResponse(responseCode = "400", description = "Non-positive amount, or the "
                    + "centre has no wallet.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Map<String, Object>> credit(
            @PathVariable String centrePublicId,
            @Parameter(description = "Naira.", example = "500000", required = true)
            @RequestParam BigDecimal amount,
            @Parameter(description = "The funding instrument.", example = "FNPH/PROG/2026/Q4/017")
            @RequestParam(required = false) String reference,
            @Parameter(description = "What this funds.", required = true)
            @RequestParam String description) {

        var centre = centreRepository.findByPublicId(centrePublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such centre"));

        // Known before the call, so the screen can say a resubmission added
        // nothing instead of implying a second credit landed.
        boolean replayed = reference != null
                && ledgerRepository.findByTransactionReference(reference.trim()).isPresent();
        WalletTransaction entry = walletService.credit(
                centre.getId(), amount, reference, description);

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("centre", centre.getName());
        body.put("amount", entry.getAmount());
        body.put("balance", entry.getBalanceAfter());
        body.put("reference", entry.getTransactionReference());
        body.put("replayed", replayed);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/wallets")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).WALLET_READ_BALANCE)")
    @Operation(
            summary = "Every centre wallet balance",
            description = """
                    Balance and roughly how many bookings each centre has left.

                    **The bookings figure is the useful one.** "About four consultations
                    remaining" is what a person acts on; a naira balance is what they then
                    have to do arithmetic on.

                    Balances are derived from the ledger, not read from a cached field, so
                    this cannot silently disagree with the transaction history.

                    **Requires** `wallet.read_balance`, held by Finance and the Central
                    Administrator. No centre role holds it.
                    """)
    @ApiResponse(responseCode = "200", description = "Balances returned.")
    public ResponseEntity<List<Map<String, Object>>> wallets() {
        return ResponseEntity.ok(centreRepository.findAll().stream().map(centre -> {
            BigDecimal balance = walletService.balanceOf(centre.getId());
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("centre", centre.getName());
            row.put("centrePublicId", centre.getPublicId());
            row.put("balance", balance);
            row.put("canCoverNextBooking", walletService.canCoverBooking(centre.getId()));
            return row;
        }).toList());
    }

    @GetMapping("/wallets/{centrePublicId}/ledger")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).WALLET_READ_LEDGER)")
    @Operation(
            summary = "A centre's wallet ledger",
            description = """
                    Every credit and debit, newest first, with a running balance.

                    Append-only. A debit here is an approved booking and cannot be reversed;
                    a correction is a compensating credit with its own reason, so the
                    history shows what happened rather than what somebody wished had.

                    **Requires** `wallet.read_ledger`.
                    """)
    @ApiResponse(responseCode = "200", description = "Ledger returned.")
    public ResponseEntity<List<Map<String, Object>>> ledger(
            @PathVariable String centrePublicId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        var centre = centreRepository.findByPublicId(centrePublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such centre"));
        var wallet = walletRepository.findByCentreId(centre.getId())
                .orElseThrow(() -> new EntityNotFoundException("That centre has no wallet"));

        return ResponseEntity.ok(ledgerRepository
                .findAllByWalletIdOrderByIdDesc(wallet.getId(),
                        PageRequest.of(page, Math.min(size, 200)))
                .map(t -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("reference", t.getTransactionReference());
                    row.put("direction", t.getDirection().name());
                    row.put("amount", t.getAmount());
                    row.put("balanceAfter", t.getBalanceAfter());
                    row.put("description", t.getDescription());
                    row.put("source", t.getSource());
                    row.put("at", t.getCreatedAt());
                    return row;
                }).getContent());
    }

    @GetMapping("/wallets/alerts")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).WALLET_READ_BALANCE)")
    @Operation(
            summary = "Open low-balance alerts",
            description = """
                    One per centre per threshold crossing, not one per booking.

                    Firing on every booking below the threshold would produce dozens of
                    identical warnings in a morning and train Finance to ignore all of them,
                    which is worse than not alerting at all.

                    An alert clears automatically when a top-up takes the balance back above
                    its threshold.

                    **Requires** `wallet.read_balance`.
                    """)
    @ApiResponse(responseCode = "200", description = "Open alerts, oldest first.")
    // Each alert names its centre, which is lazy.
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> alerts() {
        return ResponseEntity.ok(alertRepository.findAllByClearedAtIsNullOrderByRaisedAtAsc()
                .stream().map(a -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("centre", a.getCentre().getName());
                    row.put("level", a.getAlertLevel());
                    row.put("balance", a.getBalanceAtAlert());
                    row.put("threshold", a.getThresholdAmount());
                    row.put("raisedAt", a.getRaisedAt());
                    return row;
                }).toList());
    }

    @GetMapping("/payments")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PAYMENT_READ)")
    @Operation(
            summary = "Payments in a period",
            description = """
                    For monitoring and reconciliation.

                    **Finance cannot activate a payment.** No such permission exists anywhere
                    in the matrix, which is the strongest way to hold the rule that a normal
                    successful payment is never manually activated. A payment that will not
                    verify is an exception to resolve, not a switch to flip.

                    **Requires** `payment.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Payments returned.")
    public ResponseEntity<List<Map<String, Object>>> payments(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        LocalDateTime start = from == null ? LocalDateTime.now().minusDays(30) : from;
        LocalDateTime end = to == null ? LocalDateTime.now() : to;

        return ResponseEntity.ok(paymentRepository
                .search(start, end, status, PageRequest.of(page, Math.min(size, 200)))
                .map(p -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("reference", p.getReference());
                    row.put("rrr", p.getRrr());
                    row.put("status", p.getStatus().name());
                    row.put("amount", p.getAmount());
                    row.put("creditApplied", p.getCreditApplied());
                    row.put("amountMismatch", Boolean.TRUE.equals(p.getAmountMismatch()));
                    row.put("reportedAmount", p.getReportedAmount());
                    row.put("verifiedAt", p.getVerifiedAt());
                    return row;
                }).getContent());
    }

    @PostMapping("/reconciliation/run")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PAYMENT_RECONCILE)")
    @Operation(
            summary = "Reconcile a period against Remita",
            description = """
                    Compares every payment in the window against what the provider says.

                    **This is the sweep that catches lost callbacks.** A patient pays, the
                    notification never arrives, the payment sits PENDING and they cannot
                    book. Nobody finds out unless they complain, and the ones who do not
                    complain simply do not come back. A payment Remita confirms is verified
                    here automatically, because that is the same server-to-server check the
                    callback would have triggered.

                    **Everything else becomes an exception for a person.** Nothing is
                    corrected silently: an underpayment is not a rounding issue, an
                    overpayment means the patient is owed a credit, and a payment the
                    provider has no record of is a question rather than an answer.

                    A provider that is unreachable is not an exception. Recording it as one
                    would fill this queue with noise every time Remita has a bad afternoon.

                    Runs nightly on its own as well.

                    **Requires** `payment.reconcile`, held by Finance.
                    """)
    @ApiResponse(responseCode = "200", description = "Run complete, with counts.")
    public ResponseEntity<Map<String, Object>> reconcile(
            @Parameter(description = "From. Defaults to 7 days ago.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "To. Defaults to now.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {

        var run = reconciliationService.run(
                from == null ? LocalDateTime.now().minusDays(7) : from,
                to == null ? LocalDateTime.now() : to,
                "MANUAL");

        return ResponseEntity.ok(Map.of(
                "publicId", run.getPublicId(),
                "checked", run.getTransactionsChecked(),
                "matched", run.getMatchedCount(),
                "exceptions", run.getExceptionCount()));
    }

    @GetMapping("/exceptions")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PAYMENT_EXCEPTION_HANDLE)")
    @Operation(
            summary = "Unresolved payment discrepancies",
            description = """
                    Amount mismatches, payments still pending past their window, and anything
                    the provider reports that this system has no order for.

                    **Nothing here is resolved automatically.** An underpayment is not a
                    rounding issue and an overpayment means the patient is owed a credit.
                    Either way it is a decision, and it needs a person and a recorded reason.

                    **Requires** `payment.exception_handle`.
                    """)
    @ApiResponse(responseCode = "200", description = "Open exceptions, oldest first.")
    public ResponseEntity<List<Map<String, Object>>> exceptions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        return ResponseEntity.ok(exceptionRepository
                .findAllByResolvedAtIsNullOrderByCreatedAtAsc(
                        PageRequest.of(page, Math.min(size, 200)))
                .map(e -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("publicId", e.getPublicId());
                    row.put("type", e.getExceptionType().name());
                    row.put("expected", e.getExpectedAmount());
                    row.put("reported", e.getReportedAmount());
                    row.put("details", e.getDetails());
                    row.put("raisedAt", e.getCreatedAt());
                    return row;
                }).getContent());
    }

    @PostMapping("/exceptions/{exceptionPublicId}/resolve")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PAYMENT_EXCEPTION_HANDLE)")
    @Operation(
            summary = "Record the decision on a discrepancy",
            description = """
                    Closes the exception with what was decided and why.

                    The notes are the record. A discrepancy closed with no explanation tells
                    the next person who meets the same pattern nothing, and reconciliation
                    reporting is built from these.

                    **Requires** `payment.exception_handle`.
                    """)
    @ApiResponse(responseCode = "204", description = "Resolved.")
    public ResponseEntity<Void> resolveException(
            @PathVariable String exceptionPublicId,
            @Parameter(description = "What was decided and why.", required = true)
            @RequestParam String notes) {

        var exception = exceptionRepository.findByPublicId(exceptionPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such exception"));

        exception.setResolvedAt(LocalDateTime.now());
        exception.setResolvedBy(
                com.fnph.telepsychiatric.security.CurrentUser.usernameOrSystem());
        exception.setResolutionNotes(notes);
        exceptionRepository.save(exception);
        return ResponseEntity.noContent().build();
    }
}
