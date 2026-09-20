package com.fnph.telepsychiatric.payment.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(
        name = "Payment",
        description = "A consultation payment and its current status."
)
public record PaymentResponse(

        @Schema(
                example = "01M1X5FF5ZR2M84M6088QR0FGE"
        )
        String publicId,

        @Schema(
                description = "Internal FNPH payment reference. Use this to verify the payment.",
                example = "FNPH-A7K2M9PQR4"
        )
        String reference,

        @Schema(
                description = """
                        Legacy Remita Retrieval Reference.
                        
                        Connect Gateway payments do not necessarily return an RRR,
                        therefore this may be null for Connect Gateway transactions.
                        """,
                example = "280012345678",
                nullable = true
        )
        String rrr,

        @Schema(
                description = """
                        Connect Gateway payment URL.
                        
                        In test mode, status SUCCESS is accepted immediately from
                        Connect Gateway charge status 00, so the frontend should
                        proceed to booking instead of opening this URL.
                        """,
                example = "https://payment-link-demo.systemspecsng.com/REM_xxxxxxxxx",
                nullable = true
        )
        String paymentLink,

        @Schema(
                description = """
                        Payment state.
                        
                        PENDING means payment has not been confirmed.
                        SUCCESS means the payment has been accepted.
                        FAILED means payment failed.
                        UNMATCHED means the amount reported by the provider
                        differs from the expected amount.
                        """,
                allowableValues = {
                        "PENDING",
                        "SUCCESS",
                        "FAILED",
                        "REVERSED",
                        "REFUNDED",
                        "UNMATCHED"
                },
                example = "SUCCESS"
        )
        String status,

        @Schema(
                description = "The full consultation fee.",
                example = "10000.00"
        )
        BigDecimal amount,

        @Schema(
                description = "Credit carried forward from an earlier unused payment.",
                example = "0.00"
        )
        BigDecimal creditApplied,

        @Schema(
                description = "What is actually owed after existing credit.",
                example = "10000.00"
        )
        BigDecimal payableAmount,

        @Schema(
                example = "NGN"
        )
        String currency,

        LocalDateTime initiatedAt,

        @Schema(
                description = "When payment was confirmed. Null until confirmed.",
                nullable = true
        )
        LocalDateTime verifiedAt,

        @Schema(
                description = "When the payment reference expires."
        )
        LocalDateTime expiresAt,

        @Schema(
                nullable = true
        )
        String failureReason,

        @Schema(
                description = "Whether the provider reported an amount different from expected.",
                example = "false"
        )
        boolean amountMismatch,

        @Schema(
                description = """
                        Whether this payment has already been used for an appointment.
                        A SUCCESS payment with false here is available for booking.
                        """,
                example = "false"
        )
        boolean usedForBooking
) {
}