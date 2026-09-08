package com.fnph.telepsychiatric.authz.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(name = "UserRoleAssignment",
        description = "The roles held by one account, and which of them decides where they land after sign-in.")
public record UserRoleAssignmentResponse(

        @Schema(description = "The user's public identifier.", example = "01M1X5FF5ZR2M84M6088QR0FGE")
        String userPublicId,

        @Schema(description = "Sign-in identifier.", example = "dr.bello")
        String username,

        @Schema(description = "Display name.", example = "Aisha Bello")
        String displayName,

        @Schema(description = "The single role that decides the post-login destination. "
                + "Exactly one, enforced by a database constraint.",
                example = "DOCTOR")
        String primaryRole,

        @Schema(description = "Where this user is sent after authenticating.", example = "/clinical")
        String dashboardRoute,

        @Schema(description = "Every role held, primary first.")
        List<HeldRole> roles,

        @Schema(description = "Flattened permission codes across every role held. "
                + "This is what the account can actually do.")
        List<String> effectivePermissions
) {

    @Schema(name = "HeldRole", description = "One role assignment with its grant audit.")
    public record HeldRole(

            @Schema(description = "Role code.", example = "DOCTOR")
            String code,

            @Schema(description = "Role display name.", example = "Doctor")
            String name,

            @Schema(description = "Whether this role decides the post-login destination.", example = "true")
            boolean primary,

            @Schema(description = "When the role was granted (UTC).")
            LocalDateTime grantedAt,

            @Schema(description = "Who granted it.", example = "admin.yakubu")
            String grantedBy,

            @Schema(description = "Why it was granted. Required for any non-routine assignment.",
                    example = "Covering the Tuesday clinic while Dr Musa is on leave")
            String grantReason
    ) {
    }
}
