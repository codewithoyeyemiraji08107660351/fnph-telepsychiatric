package com.fnph.telepsychiatric.session.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "ForgotPasswordRequest", description = "Ask for a password reset link.")
public record ForgotPasswordRequest(

        @NotBlank(message = "Enter your username or email address")
        @Schema(description = "Username or email address.", example = "dr.bello",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String identifier
) {
}
