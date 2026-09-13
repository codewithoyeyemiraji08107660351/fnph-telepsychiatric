package com.fnph.telepsychiatric.user.api;

import com.fnph.telepsychiatric.authz.UserRoleRepository;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.session.SessionService;
import com.fnph.telepsychiatric.user.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@Tag(name = "Administration — User Accounts")
public class UserAdministrationController {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final StaffInvitationService invitationService;

    @GetMapping
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).USER_READ)")
    @Operation(
            summary = "Staff and centre accounts",
            description = """
                    Accounts with their status and primary role.

                    **Patient accounts are excluded.** A patient is a clinical record, not a
                    staff directory entry, and this list is used for assignment and support.
                    Reading a patient goes through `patient.read`, which is audited by
                    patient.

                    Held by the Central Administrator, ICT Support and Helpdesk. ICT and
                    Helpdesk need it to answer "I cannot sign in" without also being able to
                    read anybody's clinical record.

                    **Requires** `user.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Accounts returned.")
    public ResponseEntity<List<Map<String, Object>>> list(
            @Parameter(description = "Username or name fragment.")
            @RequestParam(required = false) String term,
            @Parameter(description = "Filter by status.", example = "ACTIVE")
            @RequestParam(required = false) UserStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        var pageable = PageRequest.of(page, Math.min(size, 200));

        return ResponseEntity.ok(userRepository.searchStaff(
                        term == null ? null : term.trim(), status, pageable)
                .stream().map(this::toRow).toList());
    }

    @GetMapping("/{userPublicId}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).USER_READ)")
    @Operation(summary = "One account", description = "**Requires** `user.read`.")
    @ApiResponse(responseCode = "200", description = "Account returned.")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String userPublicId) {
        return ResponseEntity.ok(toRow(require(userPublicId)));
    }

    @PutMapping("/{userPublicId}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).USER_UPDATE)")
    @Operation(
            summary = "Correct an account's details",
            description = """
                    Name, email and phone.

                    **The username is not editable.** It is the account identity, and for a
                    patient it is their EHR number, so changing it would break the link to
                    the hospital record and orphan every audit entry that names it.

                    Roles are changed through `/admin/users/{id}/roles`, which is a separate
                    permission because assigning a role is an authorisation decision rather
                    than a correction.

                    **Requires** `user.update`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated."),
            @ApiResponse(responseCode = "400", description = "That email is already in use.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @Transactional
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable String userPublicId,
            @RequestParam(required = false) String firstName,
            @RequestParam(required = false) String lastName,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String phoneNumber) {

        Users user = require(userPublicId);

        if (email != null && !email.equalsIgnoreCase(user.getEmail())) {
            userRepository.findByEmail(email).ifPresent(other -> {
                if (!other.getId().equals(user.getId())) {
                    throw new IllegalArgumentException("That email address is already in use");
                }
            });
            user.setEmail(email);
        }
        if (firstName != null && !firstName.isBlank()) {
            user.setFirstName(firstName);
        }
        if (lastName != null && !lastName.isBlank()) {
            user.setLastName(lastName);
        }
        if (phoneNumber != null) {
            user.setPhoneNumber(phoneNumber);
        }
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("updated", true));
    }

    @PostMapping("/{userPublicId}/reset-password")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).USER_RESET_PASSWORD)")
    @Operation(
            summary = "Send a password reset to an account",
            description = """
                    Sends a single-use reset link to the address on the account.

                    **No password is set here and none is returned.** An administrator who
                    could read or choose a member of staff's password could sign in as them,
                    and every clinical action they took would be indistinguishable from the
                    real person's. The link goes to the account's own email and only the
                    holder can complete it.

                    Every session belonging to that account is revoked, on the assumption
                    that a reset is being requested because something is wrong. Leaving the
                    old sessions alive would mean an attacker who already had one keeps it.

                    A reason is required and appears in the audit trail.

                    **Requires** `user.reset_password`, held by the Central Administrator and
                    ICT Support.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Reset link sent, sessions revoked."),
            @ApiResponse(responseCode = "400", description = "No reason given.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> resetPassword(
            @PathVariable String userPublicId,
            @Parameter(description = "Why. Recorded in the audit trail.", required = true)
            @RequestParam String reason) {

        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Say why the password is being reset. A reset on someone else's account "
                            + "is an act somebody may need to account for.");
        }
        invitationService.sendAdministrativeReset(userPublicId, reason);
        return ResponseEntity.noContent().build();
    }

    private Users require(String publicId) {
        return userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new EntityNotFoundException("No such account"));
    }

    private Map<String, Object> toRow(Users u) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("publicId", u.getPublicId());
        row.put("username", u.getUsername());
        row.put("fullName", u.getFirstName() + " " + u.getLastName());
        row.put("email", u.getEmail());
        row.put("phoneNumber", u.getPhoneNumber());
        row.put("status", u.getStatus().name());
        row.put("mfaEnabled", u.getMfaEnabled());
        row.put("accountLocked", u.getAccountLocked());
        row.put("failedLoginAttempts", u.getFailedLoginAttempts());
        row.put("lastLoginAt", u.getLastLoginAt());
        row.put("roles", userRoleRepository.findRoleCodesByUserId(u.getId()));
        return row;
    }
}
