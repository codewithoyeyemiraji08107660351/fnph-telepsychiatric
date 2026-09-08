package com.fnph.telepsychiatric.payment.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(name = "Payment", description = "A consultation payment and where it has got to.")
public record PaymentResponse(

        @Schema(example = "01M1X5FF5ZR2M84M6088QR0FGE")
        String publicId,

        @Schema(description = "Internal reference. Use it to verify.", example = "FNPH-A7K2M9PQR4")
        String reference,

        @Schema(description = """
                Remita Retrieval Reference. **Show this prominently.** It is what the \
                patient quotes at a bank, a POS terminal or the Remita portal, and it is \
                what any support call about the payment will be about.
                """,
                example = "280012345678", nullable = true)
        String rrr,

        @Schema(description = """
                PENDING means not yet confirmed, or Remita was unreachable when we last \
                asked. SUCCESS unlocks slot selection. FAILED means Remita reported the \
                payment did not complete. UNMATCHED means the amount differs from the \
                order and Finance is looking at it; slot selection stays locked.
                """,
                allowableValues = {"PENDING", "SUCCESS", "FAILED", "REVERSED", "REFUNDED", "UNMATCHED"},
                example = "PENDING")
        String status,

        @Schema(description = "The full consultation fee.", example = "10000.00")
        BigDecimal amount,

        @Schema(description = "Credit carried forward from an earlier unused payment.",
                example = "10000.00")
        BigDecimal creditApplied,

        @Schema(description = "What is actually owed after credit. Zero means nothing to pay "
                + "and the payment is already confirmed.",
                example = "0.00")
        BigDecimal payableAmount,

        @Schema(example = "NGN")
        String currency,

        LocalDateTime initiatedAt,

        @Schema(description = "When Remita confirmed it (UTC). Null until confirmed.",
                nullable = true)
        LocalDateTime verifiedAt,

        @Schema(description = "After this the reference expires and a new payment is needed.")
        LocalDateTime expiresAt,

        @Schema(nullable = true)
        String failureReason,

        @Schema(description = "Remita reported a different amount. Finance is reviewing it and "
                + "booking stays locked.", example = "false")
        boolean amountMismatch,

        @Schema(description = "Whether this payment has been used for a booking. A confirmed "
                + "payment with false here is what unlocks slot selection.", example = "false")
        boolean usedForBooking
) {
}
