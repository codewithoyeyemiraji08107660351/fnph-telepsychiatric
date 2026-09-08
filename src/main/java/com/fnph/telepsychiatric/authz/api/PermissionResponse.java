package com.fnph.telepsychiatric.authz.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Permission",
        description = "A single capability. The unit every authorisation decision is made against.")
public record PermissionResponse(

        @Schema(description = "Permission code, formatted module.action. This is the value that "
                + "appears in the token holder's authority list.",
                example = "appointment.approve")
        String code,

        @Schema(description = "Grouping for the administration screen.", example = "schedule")
        String module,

        @Schema(description = "What holding this permission allows.",
                example = "Approve a requested appointment")
        String description
) {
}
