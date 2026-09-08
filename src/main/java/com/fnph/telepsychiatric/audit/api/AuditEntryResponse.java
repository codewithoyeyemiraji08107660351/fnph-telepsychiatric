package com.fnph.telepsychiatric.audit.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(name = "AuditEntry", description = "One recorded action.")
public record AuditEntryResponse(

        @Schema(example = "01M1X5FF5ZR2M84M6088QR0FGE")
        String publicId,

        @Schema(description = "The account that authenticated.", example = "admin.yakubu")
        String username,

        @Schema(description = """
                The account being acted as during a supervised session. Null for an \
                ordinary action.

                When this is set, `username` did the action while viewing \
                `effectivePrincipal`'s dashboard. Both identities are recorded because \
                "who did this" has two correct answers in that case.
                """,
                example = "dr.bello", nullable = true)
        String effectivePrincipal,

        @Schema(description = "The supervised session this happened inside, if any.",
                nullable = true)
        Long viewAsSessionId,

        @Schema(example = "PRESCRIPTION_ISSUED")
        String action,

        @Schema(example = "Prescription", nullable = true)
        String entityType,

        @Schema(nullable = true)
        Long entityId,

        @Schema(description = "What happened, in readable terms.", nullable = true)
        String details,

        @Schema(description = "Why, where the action required a stated reason.", nullable = true)
        String reason,

        @Schema(description = "Whether it succeeded or was denied.",
                allowableValues = {"SUCCESS", "DENIED"}, example = "SUCCESS")
        String outcome,

        @Schema(description = "Centre scope at the time, for tenant-filtered reporting.",
                nullable = true)
        Long centreId,

        @Schema(example = "197.210.0.0", nullable = true)
        String ipAddress,

        @Schema(description = "When it happened (UTC).")
        LocalDateTime performedAt,

        @Schema(description = "True for scheduled jobs and webhook handlers, which have no "
                + "authenticated caller.", example = "false")
        boolean systemAction
) {
}
