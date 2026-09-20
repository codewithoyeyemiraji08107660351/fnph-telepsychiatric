package com.fnph.telepsychiatric.payment.api;

import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.patient.PatientRepository;
import com.fnph.telepsychiatric.payment.PatientCreditService;
import com.fnph.telepsychiatric.payment.Payment;
import com.fnph.telepsychiatric.payment.PaymentService;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.patient.PatientJourneyService;
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
    private final PatientJourneyService journey;

    // -------------------------------------------------------------------------
    // INITIATE PAYMENT
    // -------------------------------------------------------------------------

    @PostMapping("/initiate")
    @PreAuthorize(
            "hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PAYMENT_INITIATE)"
    )
    @Operation(
            summary = "Start payment for a consultation",
            description = """
                    Creates an FNPH consultation payment and sends the payable amount
                    to Remita Connect Gateway.
                    
                    Any existing patient credit is applied first.
                    
                    The response contains:
                    
                    - reference: FNPH payment reference
                    - paymentLink: Connect Gateway hosted payment URL
                    - status: current payment status
                    - payableAmount: amount sent to Remita
                    
                    During the current test phase, a Connect Gateway charge response
                    with status 00 is accepted as SUCCESS when
                    REMITA_ACCEPT_CHARGE_SUCCESS_AS_PAID=true.
                    
                    When the payment is SUCCESS, the frontend can proceed directly
                    to consultation booking.
                    
                    Requires payment.initiate.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Payment created or existing unused payment returned.",
                    content = @Content(
                            schema = @Schema(
                                    implementation = PaymentResponse.class
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Payment could not be initiated.",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class
                            )
                    )
            )
    })
    public ResponseEntity<PaymentResponse> initiate(

            @Parameter(
                    description = "Email address to use for the payment.",
                    example = "patient@example.com"
            )
            @RequestParam(required = false)
            String email,

            @Parameter(
                    description = "Phone number to use for the payment notification.",
                    example = "08000000000"
            )
            @RequestParam(required = false)
            String phone
    ) {

        var patient =
                patientRepository
                        .findById(
                                CurrentUser.require().getPatientId()
                        )
                        .orElseThrow(
                                () -> new EntityNotFoundException(
                                        "No patient record on this account"
                                )
                        );

        /*
         * Patient must have completed the intake process before payment.
         */
        journey.requireIntake(
                patient.getId()
        );

        Payment payment =
                paymentService.initiate(
                        patient,
                        email,
                        phone
                );

        return ResponseEntity.ok(
                toResponse(payment)
        );
    }

    // -------------------------------------------------------------------------
    // VERIFY PAYMENT
    // -------------------------------------------------------------------------

    @PostMapping("/{reference}/verify")
    @PreAuthorize(
            "hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PAYMENT_READ_OWN)"
    )
    @Operation(
            summary = "Check payment status",
            description = """
                    Checks the payment status for the authenticated patient.
                    
                    The FNPH payment reference is used as the Connect Gateway
                    paymentIdentifier.
                    
                    A payment already marked SUCCESS is returned unchanged.
                    
                    During test mode, Connect Gateway charge status 00 is already
                    accepted during payment initiation, so this endpoint is
                    normally idempotent for those payments.
                    
                    Requires payment.read_own.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "Current payment state.",
            content = @Content(
                    schema = @Schema(
                            implementation = PaymentResponse.class
                    )
            )
    )
    public ResponseEntity<PaymentResponse> verify(

            @Parameter(
                    description = "Internal FNPH payment reference.",
                    example = "FNPH-A7K2M9PQR4",
                    required = true
            )
            @PathVariable
            String reference
    ) {

        Payment payment =
                paymentService.verifyForPatient(
                        reference,
                        CurrentUser.require().getPatientId()
                );

        return ResponseEntity.ok(
                toResponse(payment)
        );
    }

    // -------------------------------------------------------------------------
    // CHECKOUT
    // -------------------------------------------------------------------------

    @GetMapping("/{reference}/checkout")
    @PreAuthorize(
            "hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PAYMENT_READ_OWN)"
    )
    @Operation(
            summary = "Get the Connect Gateway payment link",
            description = """
                    Returns the hosted Remita Connect Gateway payment link associated
                    with the FNPH payment.
                    
                    This replaces the old RRR/hashed Remita checkout flow.
                    
                    In the current test configuration, the frontend should first
                    check whether the payment status is SUCCESS. If it is SUCCESS,
                    the patient should proceed directly to booking.
                    
                    The paymentLink is available for normal Connect Gateway checkout
                    when live payment confirmation is enabled.
                    
                    Requires payment.read_own.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Payment checkout information returned."
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Payment not found."
            )
    })
    public ResponseEntity<PaymentCheckoutResponse> checkout(
            @Parameter(
                    description = "Internal FNPH payment reference.",
                    example = "FNPH-A7K2M9PQR4",
                    required = true
            )
            @PathVariable
            String reference
    ) {

        Long patientId =
                CurrentUser.require().getPatientId();

        Payment payment =
                paymentService.verifyForPatient(
                        reference,
                        patientId
                );

        return ResponseEntity.ok(
                new PaymentCheckoutResponse(
                        payment.getReference(),
                        payment.getPaymentLink(),
                        payment.getStatus().name()
                )
        );
    }

    // -------------------------------------------------------------------------
    // MY PAYMENTS
    // -------------------------------------------------------------------------

    @GetMapping("/mine")
    @PreAuthorize(
            "hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PAYMENT_READ_OWN)"
    )
    @Operation(
            summary = "My payments",
            description = """
                    Returns all payments belonging to the authenticated patient,
                    newest first.
                    
                    Requires payment.read_own.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "Payments returned."
    )
    public ResponseEntity<List<PaymentResponse>> mine() {

        return ResponseEntity.ok(
                paymentService
                        .historyFor(
                                CurrentUser.require().getPatientId()
                        )
                        .stream()
                        .map(this::toResponse)
                        .toList()
        );
    }

    // -------------------------------------------------------------------------
    // CREDIT
    // -------------------------------------------------------------------------

    @GetMapping("/credit")
    @PreAuthorize(
            "hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PAYMENT_READ_OWN)"
    )
    @Operation(
            summary = "My credit balance and history",
            description = """
                    Returns the authenticated patient's available payment credit
                    and credit history.
                    
                    Credit is automatically applied to a future consultation payment.
                    
                    Requires payment.read_own.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "Balance and ledger returned.",
            content = @Content(
                    schema = @Schema(
                            implementation = CreditBalanceResponse.class
                    )
            )
    )
    public ResponseEntity<CreditBalanceResponse> credit() {

        Long patientId =
                CurrentUser.require().getPatientId();

        BigDecimal balance =
                creditService.balanceFor(
                        patientId
                );

        List<CreditBalanceResponse.CreditEntry> entries =
                creditService
                        .historyFor(patientId)
                        .stream()
                        .map(
                                c -> new CreditBalanceResponse.CreditEntry(
                                        c.getDirection().name(),
                                        c.getAmount(),
                                        c.getBalanceAfter(),
                                        c.getReason(),
                                        c.getExpiresAt(),
                                        c.getCreatedAt()
                                )
                        )
                        .toList();

        return ResponseEntity.ok(
                new CreditBalanceResponse(
                        balance,
                        "NGN",
                        entries
                )
        );
    }

    // -------------------------------------------------------------------------
    // RESPONSE MAPPING
    // -------------------------------------------------------------------------

    private PaymentResponse toResponse(
            Payment p
    ) {

        BigDecimal amount =
                p.getAmount() == null
                        ? BigDecimal.ZERO
                        : p.getAmount();

        BigDecimal creditApplied =
                p.getCreditApplied() == null
                        ? BigDecimal.ZERO
                        : p.getCreditApplied();

        BigDecimal payableAmount =
                amount.subtract(
                        creditApplied
                );

        return new PaymentResponse(
                p.getPublicId(),
                p.getReference(),
                p.getRrr(),
                p.getPaymentLink(),
                p.getStatus().name(),
                amount,
                creditApplied,
                payableAmount,
                p.getCurrency(),
                p.getInitiatedAt(),
                p.getVerifiedAt(),
                p.getExpiresAt(),
                p.getFailureReason(),
                Boolean.TRUE.equals(
                        p.getAmountMismatch()
                ),
                p.getAppointment() != null
        );
    }

    // -------------------------------------------------------------------------
    // CHECKOUT RESPONSE
    // -------------------------------------------------------------------------

    /**
     * Connect Gateway checkout response.
     *
     * The old implementation returned:
     *
     * url
     * merchantId
     * rrr
     * hash
     * responseUrl
     *
     * Those fields belong to the old Remita RRR/eChannel integration and are
     * no longer required for Connect Gateway.
     */
    public record PaymentCheckoutResponse(

            String reference,

            String paymentLink,

            String status

    ) {
    }
}