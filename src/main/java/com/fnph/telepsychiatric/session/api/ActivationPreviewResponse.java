package com.fnph.telepsychiatric.session.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(name = "ActivationPreview",
        description = "Who this invitation is for, so the page can greet them before they set a password.")
public record ActivationPreviewResponse(

        @Schema(description = "The invited person's name.", example = "Aisha Bello")
        String fullName,

        @Schema(description = "The address the invitation was sent to.", example = "a.bello@fnphkaduna.gov.ng")
        String email,

        @Schema(description = "The username they will sign in with.", example = "dr.bello")
        String username,

        @Schema(description = "When this link stops working (UTC).")
        LocalDateTime expiresAt,

        @Schema(description = "Minimum password length to enforce in the form.", example = "12")
        int minimumPasswordLength
) {
}
