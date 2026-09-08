package com.fnph.telepsychiatric.user.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "DeactivateUserRequest", description = "Remove an account's access without removing its history.")
public record DeactivateUserRequest(

        @NotBlank(message = "A reason is required")
        @Size(min = 10, max = 500, message = "Give a reason of at least 10 characters")
        @Schema(description = "Why access is being removed. Written to the audit trail and included "
                + "in the notice sent to the account holder.",
                example = "Left the department on 30 September 2026",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String reason
) {
}
