package com.fnph.telepsychiatric.session.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "CompleteActivationRequest",
        description = "Finish setting up an invited account by choosing a password.")
public record CompleteActivationRequest(

        @NotBlank(message = "The activation token is required")
        @Schema(description = "The token from the invitation link.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String token,

        @NotBlank(message = "Choose a password")
        @Size(min = 12, max = 200, message = "Use at least 12 characters")
        @Schema(description = "At least 12 characters. No password was emailed to you; "
                + "you are choosing it now, and it is never sent anywhere.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String password
) {
}
