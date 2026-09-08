package com.fnph.telepsychiatric.center.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(name = "CentreCapability",
        description = "Whether an optional local role is activated at a centre, and the audit around it.")
public record CentreCapabilityResponse(

        @Schema(description = "Which capability.", allowableValues = {"PHARMACY", "LABORATORY", "HIM"},
                example = "PHARMACY")
        String capability,

        @Schema(description = "The role this capability unlocks at the centre.",
                example = "CENTRE_PHARMACY")
        String unlocksRole,

        @Schema(description = "Whether it is currently active.", example = "false")
        boolean enabled,

        @Schema(description = "When it was last activated (UTC).", nullable = true)
        LocalDateTime enabledAt,

        @Schema(description = "Who activated it.", nullable = true)
        String enabledBy,

        @Schema(description = "The review that justified activation.", nullable = true)
        String enableReason,

        @Schema(description = "When it was last withdrawn (UTC).", nullable = true)
        LocalDateTime disabledAt,

        @Schema(description = "Who withdrew it.", nullable = true)
        String disabledBy,

        @Schema(description = "Why it was withdrawn.", nullable = true)
        String disableReason
) {
}
