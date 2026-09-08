package com.fnph.telepsychiatric.authz.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(name = "AssignRolesRequest",
        description = """
                Replaces a user's roles wholesale rather than adding one at a time.

                Replacement is deliberate. Incremental grants accumulate: an account
                that once covered a clinic keeps the extra role for years because
                nobody remembers to remove it. Sending the complete intended set makes
                every review an explicit decision about the whole account.
                """)
public record AssignRolesRequest(

        @NotEmpty(message = "At least one role is required")
        @Schema(description = "The complete set of role codes this user should hold after the call. "
                + "Any role not listed is removed.",
                example = "[\"DOCTOR\", \"HUB_COORDINATOR\"]",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> roleCodes,

        @NotBlank(message = "A primary role is required")
        @Schema(description = "Which of the roles above decides the post-login destination. "
                + "Must appear in roleCodes.",
                example = "DOCTOR",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String primaryRoleCode,

        @NotBlank(message = "A reason is required")
        @Size(min = 10, max = 500, message = "Give a reason of at least 10 characters")
        @Schema(description = """
                Why this change is being made. Written to the audit trail with the
                administrator's identity. A minimum length is enforced because
                "update" tells a future auditor nothing.
                """,
                example = "Covering the Tuesday clinic while Dr Musa is on leave until 30 September",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String reason
) {
}
