package com.fnph.telepsychiatric.configuration.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(name = "Configuration", description = "One governed setting and its bounds.")
public record ConfigurationResponse(

        @Schema(description = "The key, as used in code.", example = "no_show_cutoff_minutes")
        String key,

        @Schema(description = "Current value, as text. Interpret using `valueType`.", example = "15")
        String value,

        @Schema(allowableValues = {"INTEGER", "DECIMAL", "BOOLEAN", "STRING", "DURATION_MINUTES"},
                example = "INTEGER")
        String valueType,

        @Schema(description = "Grouping for the administration screen.", example = "consultation")
        String category,

        @Schema(description = "What this setting controls.",
                example = "How long after the start time the link deactivates and a no-show is recorded")
        String description,

        @Schema(description = "Lowest permitted value, where bounded. Enforce in the form.",
                example = "5", nullable = true)
        String minValue,

        @Schema(description = "Highest permitted value, where bounded.", example = "30", nullable = true)
        String maxValue,

        @Schema(description = "Comma-separated permitted values, where the setting is a choice.",
                example = "true,false", nullable = true)
        String allowedValues,

        @Schema(description = """
                True when this value is owned by FNPH governance rather than operations.

                It does not block the change. It tells the administrator that approval \
                should exist, so the reason they type records that they were told.
                """,
                example = "true")
        boolean requiresGovernance,

        @Schema(description = "When the current value took effect (UTC).")
        LocalDateTime effectiveFrom
) {
}
