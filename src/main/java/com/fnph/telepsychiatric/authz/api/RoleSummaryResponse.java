package com.fnph.telepsychiatric.authz.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "RoleSummary",
        description = "A role and how many permissions it carries. Used for the matrix overview.")
public record RoleSummaryResponse(

        @Schema(description = "Stable machine identifier. Never renamed, safe to store in a client.",
                example = "HUB_COORDINATOR")
        String code,

        @Schema(description = "Human-readable name for display.", example = "Hub Coordinator")
        String name,

        @Schema(description = "What this role is responsible for.",
                example = "Booking approval, assignment, session oversight and bundle release")
        String description,

        @Schema(description = """
                Security boundary. FNPH is hospital staff, PATIENT is a verified
                FNPH Kaduna patient, CENTRE is Centre of Excellence staff bound to
                exactly one centre. A credential from one boundary is rejected by
                the others.
                """,
                allowableValues = {"FNPH", "PATIENT", "CENTRE"}, example = "FNPH")
        String scope,

        @Schema(description = """
                Where authentication lands a user holding this as their primary role.
                The client navigates straight here; no role selector is shown.
                """,
                example = "/hub")
        String dashboardRoute,

        @Schema(description = "Seeded by migration. A system role may be adjusted but never deleted, "
                + "because clinical and audit history references it.", example = "true")
        boolean systemRole,

        @Schema(description = "An inactive role grants nothing and cannot be assigned.", example = "true")
        boolean active,

        @Schema(description = "How many permissions this role currently grants.", example = "40")
        long permissionCount
) {
}
