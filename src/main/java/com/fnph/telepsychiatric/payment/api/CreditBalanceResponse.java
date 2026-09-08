package com.fnph.telepsychiatric.payment.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Schema(name = "CreditBalance",
        description = """
                Money already paid that was not used, held against the next booking.

                Applied automatically at the next payment. The patient does nothing.
                """)
public record CreditBalanceResponse(

        @Schema(description = "Available now, excluding anything expired.", example = "10000.00")
        BigDecimal balance,

        @Schema(example = "NGN")
        String currency,

        @Schema(description = "Every credit and every use, newest first.")
        List<CreditEntry> entries
) {

    @Schema(name = "CreditEntry", description = "One movement on the credit ledger.")
    public record CreditEntry(

            @Schema(description = "CREDIT added it, DEBIT used it.",
                    allowableValues = {"CREDIT", "DEBIT"}, example = "CREDIT")
            String direction,

            @Schema(example = "10000.00")
            BigDecimal amount,

            @Schema(description = "Running balance after this movement.", example = "10000.00")
            BigDecimal balanceAfter,

            @Schema(description = "Why. A patient asking where their money went reads this.",
                    example = "Booking not confirmed by the hospital on 12 September 2026")
            String reason,

            @Schema(description = "When it expires. Null means it does not.", nullable = true)
            LocalDateTime expiresAt,

            LocalDateTime createdAt
    ) {
    }
}
