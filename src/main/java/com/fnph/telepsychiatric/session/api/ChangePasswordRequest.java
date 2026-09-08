package com.fnph.telepsychiatric.session.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "ChangePasswordRequest", description = "Change your own password while signed in.")
public record ChangePasswordRequest(

        @NotBlank(message = "Enter your current password")
        @Schema(description = "Your current password. Required so a walk-up to an unlocked "
                + "workstation cannot silently take over the account.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String currentPassword,

        @NotBlank(message = "Enter a new password")
        @Size(min = 12, max = 200, message = "Use at least 12 characters")
        @Schema(description = "At least 12 characters, and not the one you are replacing.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String newPassword
) {
}
