package com.fnph.telepsychiatric.authz.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(name = "RoleDetail",
        description = "A role with the full set of permissions it grants, grouped by module.")
public record RoleDetailResponse(

        @Schema(description = "Stable machine identifier.", example = "PHARMACIST")
        String code,

        @Schema(description = "Human-readable name.", example = "Pharmacist")
        String name,

        @Schema(description = "What this role is responsible for.")
        String description,

        @Schema(description = "Security boundary.", allowableValues = {"FNPH", "PATIENT", "CENTRE"})
        String scope,

        @Schema(description = "Post-authentication destination for this role.", example = "/reviews/pharmacy")
        String dashboardRoute,

        @Schema(description = "Total permissions granted.", example = "15")
        int permissionCount,

        @Schema(description = """
                Permissions granted, keyed by module. Absence is meaningful:
                the Pharmacist role deliberately has no `clinical.clinical_note.read`,
                because pharmacy sees patient identity, permitted biodata, vitals and
                submitted material, but never the doctor's clinical note.
                """)
        Map<String, List<PermissionResponse>> permissionsByModule
) {
}
