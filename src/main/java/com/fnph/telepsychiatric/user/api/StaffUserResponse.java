package com.fnph.telepsychiatric.user.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(name = "StaffUser", description = "A staff or centre account and where it is in the invitation flow.")
public record StaffUserResponse(

        @Schema(description = "Public identifier for this account.", example = "01M1X5FF5ZR2M84M6088QR0FGE")
        String publicId,

        @Schema(example = "dr.bello")
        String username,

        @Schema(example = "a.bello@fnphkaduna.gov.ng")
        String email,

        @Schema(example = "Aisha Bello")
        String fullName,

        @Schema(description = """
                INVITED means the invitation was sent but no password is set and the
                account cannot sign in. ACTIVE means it has been taken up. SUSPENDED is
                a reversible bar. DEACTIVATED removes access while keeping all history.
                """,
                allowableValues = {"INVITED", "ACTIVE", "SUSPENDED", "DEACTIVATED"}, example = "INVITED")
        String status,

        @Schema(example = "DOCTOR")
        String primaryRoleCode,

        @Schema(example = "Doctor")
        String primaryRoleName,

        @Schema(description = "Centre this account is bound to, for centre roles only.", nullable = true)
        String centrePublicId,

        @Schema(description = "Centre name, for centre roles only.", nullable = true)
        String centreName,

        @Schema(description = "Whether a second factor is required. True for every staff and centre "
                + "account; it is enrolled at first sign-in.", example = "true")
        boolean mfaRequired,

        @Schema(description = "When the invitation was sent (UTC).")
        LocalDateTime invitedAt,

        @Schema(description = "Who sent it.", example = "admin.yakubu")
        String invitedBy,

        @Schema(description = "When the current invitation link stops working (UTC). "
                + "Resending issues a new link and invalidates this one.", nullable = true)
        LocalDateTime invitationExpiresAt,

        @Schema(description = "When the invitation was taken up. Null while still INVITED.", nullable = true)
        LocalDateTime activatedAt
) {
}
