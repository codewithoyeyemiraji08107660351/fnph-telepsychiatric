package com.fnph.telepsychiatric.session.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "ResetPasswordRequest", description = "Set a new password using a reset link.")
public record ResetPasswordRequest(

        @NotBlank(message = "The reset token is required")
        @Schema(description = "The token from the link in the reset email.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String token,

        @NotBlank(message = "Enter a new password")
        @Size(min = 12, max = 200, message = "Use at least 12 characters")
        @Schema(description = """
                At least 12 characters. Length is the requirement, not a mix of
                character types: forcing an uppercase, a digit and a symbol reliably
                produces Password1! and nothing better. A short phrase you can
                remember is stronger and less likely to be written down.
                """,
                example = "correct horse battery staple",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String password
) {
}
