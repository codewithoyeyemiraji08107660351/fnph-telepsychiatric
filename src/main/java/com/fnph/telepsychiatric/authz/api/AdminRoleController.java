package com.fnph.telepsychiatric.authz.api;

import com.fnph.telepsychiatric.authz.Permissions;
import com.fnph.telepsychiatric.authz.RoleScope;
import com.fnph.telepsychiatric.authz.RoleService;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Tag(name = "Administration — Roles & Permissions")
public class AdminRoleController {

    private final RoleService roleService;

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).ROLE_READ)")
    @Operation(
            summary = "List roles",
            description = """
                    Returns every active role with the number of permissions it grants.

                    Roles are seeded by migration and marked as system roles. A system
                    role may have its permissions adjusted but it cannot be deleted,
                    because clinical, financial and audit history references it.

                    Filter by `scope` to see one security boundary at a time. The three
                    boundaries are strictly separated: a centre credential is rejected by
                    the patient application, and an account may never hold roles from more
                    than one boundary.

                    **Requires** `role.read`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Roles returned.",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = RoleSummaryResponse.class)))),
            @ApiResponse(responseCode = "401", description = "No valid access token was presented.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Authenticated but lacking `role.read`.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<List<RoleSummaryResponse>> listRoles(
            @Parameter(description = "Restrict to one security boundary. Omit for all.",
                    example = "CENTRE")
            @RequestParam(required = false) RoleScope scope) {
        return ResponseEntity.ok(roleService.listRoles(scope));
    }

    @GetMapping("/roles/{code}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).ROLE_READ)")
    @Operation(
            summary = "Get one role and everything it grants",
            description = """
                    Returns the role with its permissions grouped by module.

                    Read the absences as carefully as the grants. The Pharmacist role has
                    no `clinical_note.read`, because pharmacy sees patient identity,
                    permitted biodata, vitals and submitted pre-consultation material but
                    never the doctor's clinical note. HIM has no `appointment.approve`,
                    because HIM retrieves the record and marks the task treated with no
                    approval or rejection authority. The Central Administrator has no
                    `clinical_note.write`, because supervising a clinician is not the same
                    as writing in their name.

                    **Requires** `role.read`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role returned.",
                    content = @Content(schema = @Schema(implementation = RoleDetailResponse.class))),
            @ApiResponse(responseCode = "403", description = "Lacking `role.read`.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No role with that code.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<RoleDetailResponse> getRole(
            @Parameter(description = "Role code, not the display name.", example = "PHARMACIST",
                    required = true)
            @PathVariable String code) {
        return ResponseEntity.ok(roleService.getRole(code));
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).ROLE_READ)")
    @Operation(
            summary = "List the permission catalogue",
            description = """
                    Every permission the system recognises, grouped by module.

                    The catalogue is fixed by migration rather than created at runtime.
                    A permission that exists in one environment and not another would make
                    the same account behave differently depending on where it signed in.

                    **Requires** `role.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Catalogue returned, keyed by module.")
    public ResponseEntity<Map<String, List<PermissionResponse>>> listPermissions(
            @Parameter(description = "Restrict to one module. Omit for all.", example = "finance")
            @RequestParam(required = false) String module) {
        return ResponseEntity.ok(roleService.listPermissions(module));
    }

    @GetMapping("/users/{userPublicId}/roles")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).ROLE_READ)")
    @Operation(
            summary = "Get the roles held by one account",
            description = """
                    Returns each role held, which one is primary, the grant audit for each,
                    and the flattened set of permissions the account can actually use.

                    `effectivePermissions` is the answer to "what can this person do",
                    which is rarely obvious from the role names alone once an account holds
                    more than one.

                    **Requires** `role.read`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Assignments returned.",
                    content = @Content(schema = @Schema(implementation = UserRoleAssignmentResponse.class))),
            @ApiResponse(responseCode = "404", description = "No user with that identifier.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<UserRoleAssignmentResponse> getUserRoles(
            @Parameter(description = "The user's 26-character public identifier.",
                    example = "01M1X5FF5ZR2M84M6088QR0FGE", required = true)
            @PathVariable String userPublicId) {
        return ResponseEntity.ok(roleService.getUserRoles(userPublicId));
    }

    @PutMapping("/users/{userPublicId}/roles")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).ROLE_ASSIGN)")
    @Operation(
            summary = "Replace the roles held by one account",
            description = """
                    Sets the complete role set for an account. Any role not listed is removed.

                    Replacement rather than incremental grant is deliberate. Roles added one
                    at a time accumulate, and an account that once covered a clinic keeps the
                    extra capability for years because nobody remembers to take it back.

                    Four rules are enforced and will reject the request:

                    * The primary role must be one of the roles being granted, or the
                      post-login redirect would point somewhere the user cannot go.
                    * Every role must belong to the same security boundary. An account
                      holding both a CENTRE and an FNPH role would be a hole straight through
                      tenant isolation.
                    * A centre role requires the account to be bound to a centre, and an
                      account bound to a centre cannot hold a non-centre role.
                    * The PATIENT role requires a linked patient record, and an account with
                      one cannot hold a staff role.

                    The reason is mandatory and written to the audit trail with the
                    administrator's identity.

                    **Requires** `role.assign`, held only by the Central Administrator.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Roles replaced. Returns the new state.",
                    content = @Content(schema = @Schema(implementation = UserRoleAssignmentResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Unknown role code, primary role not in the set, roles spanning "
                            + "more than one boundary, or a boundary mismatch with the account.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Lacking `role.assign`.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No user with that identifier.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<UserRoleAssignmentResponse> assignRoles(
            @Parameter(description = "The user's 26-character public identifier.",
                    example = "01M1X5FF5ZR2M84M6088QR0FGE", required = true)
            @PathVariable String userPublicId,
            @Valid @RequestBody AssignRolesRequest request) {
        return ResponseEntity.ok(roleService.assignRoles(userPublicId, request));
    }
}
