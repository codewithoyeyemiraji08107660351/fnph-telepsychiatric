package com.fnph.telepsychiatric.center.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "CentreCapabilityRequest",
        description = "Turn an optional local role on or off at one centre.")
public record CentreCapabilityRequest(

        @NotNull(message = "Say whether the capability is being enabled or disabled")
        @Schema(description = "True to activate the capability, false to withdraw it.",
                example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        Boolean enabled,

        @NotBlank(message = "A reason is required")
        @Size(min = 10, max = 500, message = "Give a reason of at least 10 characters")
        @Schema(description = """
                Why. Enabling records the staffing and capability review that justified
                it; disabling records whether the pharmacist simply left or the centre
                failed an audit, which are very different events and should not look
                identical a year later.
                """,
                example = "Registered pharmacist in post since 1 September, competency review passed",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String reason
) {
}
