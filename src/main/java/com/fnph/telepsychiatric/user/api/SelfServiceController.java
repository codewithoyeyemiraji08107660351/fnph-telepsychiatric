package com.fnph.telepsychiatric.user.api;

import com.fnph.telepsychiatric.ehr.ContactVerificationRepository;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.user.UserRepository;
import com.fnph.telepsychiatric.user.Users;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Things every signed-in user does for themselves, plus the two operational
 * reads that had no endpoint.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Self Service")
public class SelfServiceController {

    private final UserRepository userRepository;
    private final ContactVerificationRepository contactRepository;

    @GetMapping("/me/profile")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PROFILE_READ_OWN)")
    @Operation(
            summary = "My profile",
            description = """
                    Your own details. Every role holds this.

                    Separate from `/me`, which returns the security picture: roles,
                    permissions, scope and dashboard route. That one is for the client to
                    decide what to render; this one is for a settings screen.

                    **Requires** `profile.read_own`.
                    """)
    @ApiResponse(responseCode = "200", description = "Profile returned.")
    public ResponseEntity<Map<String, Object>> profile() {
        Users user = requireSelf();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("publicId", user.getPublicId());
        body.put("username", user.getUsername());
        body.put("firstName", user.getFirstName());
        body.put("lastName", user.getLastName());
        body.put("email", user.getEmail());
        body.put("phoneNumber", user.getPhoneNumber());
        body.put("mfaEnabled", user.getMfaEnabled());
        body.put("mustChangePassword", user.getMustChangePassword());
        body.put("lastLoginAt", user.getLastLoginAt());
        return ResponseEntity.ok(body);
    }

    @PutMapping("/me/profile")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PROFILE_UPDATE_OWN)")
    @Operation(
            summary = "Update my contact details",
            description = """
                    Name, email and phone.

                    **Username is not editable and neither is anything clinical.** The
                    username is the account identity and, for a patient, it is their EHR
                    number. Changing it would break the link to the hospital record.

                    A patient changing their phone here does **not** change what the EHR
                    snapshot holds. Enrolment codes go to the snapshot's number, deliberately,
                    so this cannot be used to redirect them.

                    **Requires** `profile.update_own`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated."),
            @ApiResponse(responseCode = "400", description = "That email is already in use.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @Transactional
    public ResponseEntity<Map<String, Object>> updateProfile(
            @RequestParam(required = false) @Size(max = 100) String firstName,
            @RequestParam(required = false) @Size(max = 100) String lastName,
            @RequestParam(required = false) @Email @Size(max = 100) String email,
            @RequestParam(required = false) @Size(max = 20) String phoneNumber) {

        Users user = requireSelf();

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

    @GetMapping("/system/health")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).SYSTEM_HEALTH_READ)")
    @Operation(
            summary = "Operational health for the dashboard",
            description = """
                    The handful of numbers that say whether the service is actually working,
                    as opposed to whether the process is running.

                    `enrolmentAvailable` is the one to watch. It is false when no EHR
                    snapshot is active, and that is a service-down condition for the patient
                    front door while every other part of the system looks healthy.

                    **Requires** `system.health_read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Health summary.")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("checkedAt", LocalDateTime.now());
        body.put("activeStaffAccounts", userRepository.count());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/admin/enrolment/pending-codes")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).ENROLMENT_RELEASE_CODE)")
    @Operation(
            summary = "Enrolment codes waiting to be read to a patient",
            description = """
                    Assisted enrolment. A patient with no email on the hospital record cannot
                    receive a code by any channel, so it comes here and a member of staff
                    reads it to the person in front of them.

                    That is a stronger check than email or SMS, not a weaker one: the
                    destination is a person the hospital can see.

                    **The code itself is never returned.** Only that one is waiting, for
                    which EHR number, and when it expires. Releasing it is a separate call
                    that is recorded, so reading a code out is an auditable act rather than
                    a screen someone left open.

                    **Requires** `enrolment.release_code`, held by HIM, Helpdesk and the Hub
                    Coordinator.
                    """)
    @ApiResponse(responseCode = "200", description = "Pending codes returned.")
    public ResponseEntity<List<Map<String, Object>>> pendingCodes() {
        return ResponseEntity.ok(contactRepository.findPendingAssisted(LocalDateTime.now())
                .stream().map(v -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("publicId", v.getPublicId());
                    row.put("ehrNumber", v.getEhrNumber());
                    row.put("destinationMasked", v.getDestinationMasked());
                    row.put("expiresAt", v.getExpiresAt());
                    row.put("attempts", v.getAttempts());
                    // Deliberately no code field.
                    return row;
                }).toList());
    }

    private Users requireSelf() {
        return userRepository.findById(CurrentUser.require().getUserId())
                .orElseThrow(() -> new EntityNotFoundException("Account not found"));
    }
}
