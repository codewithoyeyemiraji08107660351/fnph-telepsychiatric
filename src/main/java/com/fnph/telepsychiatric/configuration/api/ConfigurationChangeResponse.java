package com.fnph.telepsychiatric.configuration.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(name = "ConfigurationChange", description = "One recorded change to a setting.")
public record ConfigurationChangeResponse(

        @Schema(example = "consultation_fee_ngn")
        String key,

        @Schema(description = "What it was before. Null only for the seeded initial value.",
                example = "10000", nullable = true)
        String previousValue,

        @Schema(example = "12000")
        String newValue,

        @Schema(description = "Why it changed.")
        String reason,

        @Schema(description = "Who changed it.", example = "admin.yakubu")
        String changedBy,

        @Schema(description = "When the change was made (UTC).")
        LocalDateTime changedAt,

        @Schema(description = "When the new value took or takes effect (UTC).")
        LocalDateTime effectiveFrom
) {
}
