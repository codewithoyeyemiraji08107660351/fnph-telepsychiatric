package com.fnph.telepsychiatric.session.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "MfaVerificationRequest", description = "Step two of sign-in: the second factor.")
public record MfaVerificationRequest(

        @NotBlank(message = "The sign-in token is required")
        @Schema(description = "The `mfaToken` returned by the login step.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String mfaToken,

        @NotBlank(message = "Enter the code")
        @Schema(description = """
                Six digits from the authenticator app, or one recovery code.

                A recovery code contains a hyphen, which is how the server tells them
                apart. Each is accepted once and never again.
                """,
                example = "482913", requiredMode = Schema.RequiredMode.REQUIRED)
        String code
) {
}
