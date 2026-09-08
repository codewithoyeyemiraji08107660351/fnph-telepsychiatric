package com.fnph.telepsychiatric.payment.api;

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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(name = "Payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final PatientCreditService creditService;
    private final PatientRepository patientRepository;

    @PostMapping("/initiate")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PAYMENT_INITIATE)")
    @Operation(
            summary = "Start payment for a consultation",
            description = """
                    Registers the order with Remita and returns the RRR the patient quotes
                    at any payment channel.

                    **Any credit on the account is applied first.** A patient whose previous
                    booking was rejected pays the difference, or nothing at all when the
                    credit covers the fee. `creditApplied` and `payableAmount` say which.

                    **Calling this twice does not create two payments.** If the patient
                    already has a verified payment that has not been used for a booking, it
                    is returned unchanged. Refreshing the page must not charge anyone twice.

                    When credit covers the whole fee no request goes to Remita at all and
                    the payment is immediately SUCCESS, which unlocks slot selection.

                    In the approved sequence this is step 5, after triage, vitals and
                    uploads, and before slot selection.

                    **Requires** `payment.initiate`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Payment registered, or the existing "
                    + "unused one returned.",
                    content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
            @ApiResponse(responseCode = "400", description = "Remita could not register the order.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PaymentResponse> initiate(
            @Parameter(description = "Address for the payment receipt.")
            @RequestParam(required = false) String email,
            @Parameter(description = "Number for the payment notification.")
            @RequestParam(required = false) String phone) {

        var patient = patientRepository.findById(CurrentUser.require().getPatientId())
                .orElseThrow(() -> new EntityNotFoundException("No patient record on this account"));

        return ResponseEntity.ok(toResponse(paymentService.initiate(patient, email, phone)));
    }

    @PostMapping("/{reference}/verify")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PAYMENT_READ_OWN)")
    @Operation(
            summary = "Check with Remita whether a payment completed",
            description = """
                    Asks Remita server-to-server and acts on the answer.

                    **Call this when the patient returns from the payment page, and poll it
                    while the status is PENDING.** Do not treat the return page itself as
                    proof: it is a URL the browser was sent to, and it can be opened
                    directly, replayed, or reached after a failed payment. Nothing in this
                    system moves a payment to SUCCESS except this call agreeing with the
                    order on reference, amount, currency and status.

                    Idempotent. A payment already SUCCESS is returned unchanged.

                    **A status of PENDING after this call can mean Remita was
                    unreachable**, not that the payment failed. It stays pending and
                    reconciliation picks it up. Treating unreachable as unpaid would strand
                    a patient who has paid.

                    A reported amount differing from the order sets status UNMATCHED and
                    raises a Finance exception. Slot selection stays locked until a person
                    decides, because underpayment is not a rounding issue and overpayment
                    means the patient is owed a credit.

                    **Requires** `payment.read_own`.
                    """)
    @ApiResponse(responseCode = "200", description = "Current state after checking with Remita.",
            content = @Content(schema = @Schema(implementation = PaymentResponse.class)))
    public ResponseEntity<PaymentResponse> verify(
            @Parameter(description = "The internal payment reference.", example = "FNPH-A7K2M9PQR4",
                    required = true)
            @PathVariable String reference) {
        return ResponseEntity.ok(toResponse(paymentService.verify(reference)));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PAYMENT_READ_OWN)")
    @Operation(
            summary = "My payments",
            description = """
                    Every payment on this account, newest first.

                    `creditApplied` shows where a previous non-refunded payment was carried
                    forward, which is the answer to "I paid but it says nothing is owed".

                    **Requires** `payment.read_own`.
                    """)
    @ApiResponse(responseCode = "200", description = "Payments returned.")
    public ResponseEntity<List<PaymentResponse>> mine() {
        return ResponseEntity.ok(paymentService.historyFor(CurrentUser.require().getPatientId())
                .stream().map(this::toResponse).toList());
    }

    @GetMapping("/credit")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PAYMENT_READ_OWN)")
    @Operation(
            summary = "My credit balance and how it arose",
            description = """
                    Credit is money already paid that was not used, held against the next
                    booking.

                    It arises when a booking is not confirmed: the Hub Coordinator rejects
                    the request, or the hospital cancels. Payment is non-refundable, so the
                    amount stays with FNPH, but the patient is not charged for a
                    consultation that never happened.

                    It is applied automatically at the next booking. No action is needed.

                    Each entry carries the reason it exists, so a patient asking where their
                    money went has an answer without contacting the helpdesk.

                    **Requires** `payment.read_own`.
                    """)
    @ApiResponse(responseCode = "200", description = "Balance and ledger returned.",
            content = @Content(schema = @Schema(implementation = CreditBalanceResponse.class)))
    public ResponseEntity<CreditBalanceResponse> credit() {
        Long patientId = CurrentUser.require().getPatientId();
        BigDecimal balance = creditService.balanceFor(patientId);

        List<CreditBalanceResponse.CreditEntry> entries = creditService.historyFor(patientId)
                .stream()
                .map(c -> new CreditBalanceResponse.CreditEntry(
                        c.getDirection().name(), c.getAmount(), c.getBalanceAfter(),
                        c.getReason(), c.getExpiresAt(), c.getCreatedAt()))
                .toList();

        return ResponseEntity.ok(new CreditBalanceResponse(balance, "NGN", entries));
    }

    private PaymentResponse toResponse(Payment p) {
        return new PaymentResponse(
                p.getPublicId(), p.getReference(), p.getRrr(), p.getStatus().name(),
                p.getAmount(), p.getCreditApplied(),
                p.getAmount().subtract(p.getCreditApplied()), p.getCurrency(),
                p.getInitiatedAt(), p.getVerifiedAt(), p.getExpiresAt(),
                p.getFailureReason(), Boolean.TRUE.equals(p.getAmountMismatch()),
                p.getAppointment() != null);
    }
}
