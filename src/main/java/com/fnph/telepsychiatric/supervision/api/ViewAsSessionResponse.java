package com.fnph.telepsychiatric.supervision.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(name = "ViewAsSession",
        description = "A supervised view of another user's dashboard, and what it permits.")
public record ViewAsSessionResponse(

        @Schema(description = "Identifier to pass when ending the session.",
                example = "01M1X5FF5ZR2M84M6088QR0FGE")
        String publicId,

        @Schema(description = "The account being viewed.")
        String targetUserPublicId,

        @Schema(example = "dr.bello")
        String targetUsername,

        @Schema(example = "Aisha Bello")
        String targetFullName,

        @Schema(description = "The role whose dashboard is open.", example = "DOCTOR")
        String targetRole,

        @Schema(description = "Navigate here to see what that user sees.", example = "/clinical")
        String dashboardRoute,

        @Schema(description = "The reason given when the session was opened.")
        String reason,

        @Schema(description = "When it started (UTC).")
        LocalDateTime startedAt,

        @Schema(description = "When it closes automatically if not ended (UTC). An administrator "
                + "who walks away does not leave the mode running.")
        LocalDateTime expiresAt,

        @Schema(description = "When it ended, null while open.", nullable = true)
        LocalDateTime endedAt,

        @Schema(description = "How many audited actions happened inside it.", example = "3")
        int actionsPerformed,

        @Schema(description = """
                The permissions available during this session: the target's \
                **non-mutating** permissions only.

                Supervised access is read-only. The administrator sees what the user \
                sees and cannot write a clinical note, sign one, issue a prescription, \
                approve a booking, move money or download a patient's document. If they \
                could, a note written this way would be indistinguishable from one the \
                clinician wrote, and clinician-authored content is not altered by anyone \
                else.

                Use this to grey out every control not listed. The server enforces it \
                independently, so a client that shows a disabled control gains nothing.
                """,
                example = "[\"appointment.read\", \"clinical_note.read\", \"patient.read\"]")
        List<String> availablePermissions
) {
}
