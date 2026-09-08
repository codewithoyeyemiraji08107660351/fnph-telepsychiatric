package com.fnph.telepsychiatric.supervision.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "StartViewAsRequest", description = "Open a supervised view of another user's dashboard.")
public record StartViewAsRequest(

        @NotBlank(message = "A target account is required")
        @Schema(description = "The account whose dashboard to open. Must be an active staff or "
                + "centre account; patient accounts cannot be supervised.",
                example = "01M1X5FF5ZR2M84M6088QR0FGE", requiredMode = Schema.RequiredMode.REQUIRED)
        String targetUserPublicId,

        @NotBlank(message = "A reason is required")
        @Size(min = 15, max = 500, message = "Give a reason of at least 15 characters")
        @Schema(description = """
                Why this dashboard is being opened. Written to the audit trail and shown \
                on the supervised-access report.

                A longer minimum than elsewhere is deliberate. This is the one action \
                that lets one person see another's working view, and "checking" tells a \
                future auditor nothing about whether it was justified.
                """,
                example = "Investigating helpdesk ticket 4417: Dr Bello reports the release button is disabled",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String reason
) {
}
