package com.fnph.telepsychiatric.authz.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "CurrentPrincipal",
        description = """
                Everything a client needs to render the correct interface for the
                signed-in user, in one call made immediately after authentication.

                Clients drive navigation and control visibility from `permissions`.
                That is a convenience, not a security boundary: the server enforces
                every one of these independently on each request, so a client that
                shows a hidden control gains nothing.
                """)
public record CurrentPrincipalResponse(

        @Schema(description = "Public identifier for this account.", example = "01M1X5FF5ZR2M84M6088QR0FGE")
        String publicId,

        @Schema(description = "Sign-in identifier. For a patient this is their EHR number.",
                example = "dr.bello")
        String username,

        @Schema(description = "Display name.", example = "Aisha Bello")
        String displayName,

        @Schema(description = "Security boundary this account belongs to.",
                allowableValues = {"FNPH", "PATIENT", "CENTRE"}, example = "FNPH")
        String scope,

        @Schema(description = "The role deciding the post-login destination.", example = "DOCTOR")
        String primaryRole,

        @Schema(description = "Navigate here immediately after sign-in. No role selector is shown.",
                example = "/clinical")
        String dashboardRoute,

        @Schema(description = "Every role held.", example = "[\"DOCTOR\"]")
        List<String> roles,

        @Schema(description = "Every permission held, flattened across roles.",
                example = "[\"clinical_note.write\", \"prescription.write\", \"consultation.terminate\"]")
        List<String> permissions,

        @Schema(description = "Centre this account is bound to, for centre staff only. "
                + "Null for FNPH staff and patients.",
                example = "01M1X5FF5ZR2M84M6088QR0FGF", nullable = true)
        String centrePublicId,

        @Schema(description = "Centre name, for centre staff only.",
                example = "Zaria Centre of Excellence", nullable = true)
        String centreName,

        @Schema(description = "Whether a second factor is enrolled. Required for staff and centre "
                + "administrators.", example = "true")
        boolean mfaEnabled,

        @Schema(description = "When true the client must send the user to a password change before "
                + "anything else.", example = "false")
        boolean mustChangePassword
) {
}
