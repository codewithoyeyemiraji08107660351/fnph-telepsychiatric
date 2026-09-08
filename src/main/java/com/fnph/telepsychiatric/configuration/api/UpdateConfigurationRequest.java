package com.fnph.telepsychiatric.configuration.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

@Schema(name = "UpdateConfigurationRequest", description = "Change one governed setting.")
public record UpdateConfigurationRequest(

        @NotBlank(message = "A value is required")
        @Size(max = 1000)
        @Schema(description = "The new value, as text. Checked against the setting's type, "
                + "bounds and permitted values before it is accepted.",
                example = "20", requiredMode = Schema.RequiredMode.REQUIRED)
        String value,

        @NotBlank(message = "A reason is required")
        @Size(min = 15, max = 500, message = "Give a reason of at least 15 characters")
        @Schema(description = """
                Why this is changing, and where the approval came from if the setting is \
                governance-owned.

                Kept with the previous value forever. A reconciliation dispute six months \
                from now turns on which fee was in force on a given day and who decided \
                it, and "updated" answers neither.
                """,
                example = "FNPH management committee minute 2026/09/14 approved raising the no-show cutoff to 20 minutes",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String reason,

        @Schema(description = "When the new value takes effect (UTC). Defaults to now. "
                + "Use it to schedule a fee change for the start of a month.",
                nullable = true)
        LocalDateTime effectiveFrom
) {
}
