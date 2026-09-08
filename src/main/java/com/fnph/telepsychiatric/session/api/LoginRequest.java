package com.fnph.telepsychiatric.session.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "LoginRequest", description = "Step one of sign-in: username and password.")
public record LoginRequest(

        @NotBlank(message = "Enter your username")
        @Size(max = 150)
        @Schema(description = "Username or email address. For a patient this is their EHR number, "
                + "which is written into the username at activation so all three converge on one lookup.",
                example = "dr.bello", requiredMode = Schema.RequiredMode.REQUIRED)
        String username,

        @NotBlank(message = "Enter your password")
        @Size(max = 200)
        @Schema(description = "The account password.", example = "correct horse battery staple",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String password,

        @Size(max = 120)
        @Schema(description = "Optional label for this device, shown in the user's session list "
                + "so they can recognise which one to sign out.",
                example = "Chrome on the clinic desktop")
        String deviceLabel
) {
}
