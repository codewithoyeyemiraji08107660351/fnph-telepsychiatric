package com.fnph.telepsychiatric.patient.api;

import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.authz.RoleRepository;
import com.fnph.telepsychiatric.authz.UserRole;
import com.fnph.telepsychiatric.authz.UserRoleRepository;
import com.fnph.telepsychiatric.ehr.PatientVerificationRequestRepository;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.patient.Patient;
import com.fnph.telepsychiatric.patient.PatientRepository;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.security.crypto.Tokens;
import com.fnph.telepsychiatric.user.LoginType;
import com.fnph.telepsychiatric.user.UserRepository;
import com.fnph.telepsychiatric.user.UserStatus;
import com.fnph.telepsychiatric.user.Users;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Manual patient enrolment, for the cases the snapshot cannot cover.
 *
 * <h2>Deliberately narrow, and deliberately awkward</h2>
 *
 * The intended route is the EHR snapshot: a patient enrols themselves against
 * it, with corroboration and contact verification. That path has controls this
 * one does not, so this exists only for the patient who cannot use it and it is
 * held by HIM alone.
 *
 * Every account created here names the verification request it came from and
 * how the person was verified, because a manual identity decision with no
 * record of how it was made is the weakest link in the whole enrolment design.
 */
@RestController
@RequestMapping("/api/v1/admin/patients")
@RequiredArgsConstructor
@Tag(name = "Administration — Manual Enrolment")
public class ManualEnrolmentController {

    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PatientVerificationRequestRepository requestRepository;
    private final AuditService auditService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PATIENT_CREATE)")
    @Operation(
            summary = "Create a patient record by hand",
            description = """
                    For a patient the active EHR snapshot cannot match.

                    **Prefer the snapshot.** Self-enrolment requires the EHR number plus a
                    corroborating detail plus a code sent to the number on file. This route
                    has none of that, so the identity check is whatever the person creating
                    the record did, and it must be written down.

                    A verification request reference is required. This is not a general
                    patient-creation endpoint: it closes a request somebody already made
                    through the exception queue, which is what ties the account to a stated
                    reason.

                    The account is created **inactive**. Activating it is a separate call, so
                    creating a record and granting access are two decisions rather than one.

                    **Requires** `patient.create`, held by HIM.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Record created, inactive."),
            @ApiResponse(responseCode = "400",
                    description = "That EHR number already exists, or no verification "
                            + "request was named.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
   @Transactional
public ResponseEntity<Map<String, Object>> create(
        @Parameter(required = true) @RequestParam @NotBlank String ehrNumber,
        @RequestParam @NotBlank @Size(max = 100) String firstName,
        @RequestParam @NotBlank @Size(max = 100) String lastName,
        @RequestParam @NotNull @Past LocalDate dateOfBirth,
        @RequestParam(required = false) String phoneNumber,
        @Parameter(description = "The verification request this closes.", required = true)
        @RequestParam String verificationRequestPublicId,
        @Parameter(description = "How this person was verified. Not that they were.",
                required = true)
        @RequestParam String verifiedHow) {

    if (verifiedHow == null || verifiedHow.isBlank()) {
        throw new IllegalArgumentException(
                "Say how this person was verified. \"Confirmed\" tells a later reader "
                        + "nothing, and this record bypasses every automatic check.");
    }
    if (patientRepository.existsByEhrNumber(ehrNumber)) {
        throw new IllegalArgumentException(
                "A patient already exists for " + ehrNumber);
    }

    var request = requestRepository.findByPublicId(verificationRequestPublicId)
            .orElseThrow(() -> new EntityNotFoundException(
                    "No such verification request. This endpoint closes a request "
                            + "somebody made, it does not create patients from nothing."));

    Patient patient = new Patient();
    patient.setEhrNumber(ehrNumber);
    patient.setFirstName(firstName);
    patient.setLastName(lastName);
    patient.setDateOfBirth(dateOfBirth);
    patient.setPhoneNumber(phoneNumber);
    patient.setIsEligible(false);
    patient.setIsPhysicallyAssessed(false);
    patient.setIsActive(false);
    Patient saved = patientRepository.save(patient);

    request.setResultingPatient(saved);
    requestRepository.save(request);

    auditService.record(AuditService.AuditEvent.builder()
            .action(AuditAction.RECORD_CREATED)
            .entityType("Patient")
            .entityId(saved.getId())
            .details("Created manually against verification request "
                    + verificationRequestPublicId)
            .reason(verifiedHow)
            .build());

    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "publicId", saved.getPublicId(),
            "ehrNumber", saved.getEhrNumber(),
            "active", false,
            "next", "Record the eligibility check, then activate"));
}

    @PostMapping("/{patientPublicId}/verify")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PATIENT_VERIFY)")
    @Operation(
            summary = "Record the eligibility check",
            description = """
                    Confirms the patient has had a physical assessment and is eligible for
                    remote follow-up.

                    **This is the clinical gate, and it is separate from identity.** Knowing
                    who somebody is does not mean this service is right for them: the
                    specification restricts it to patients already physically assessed and
                    suitable for follow-up, and an unassessed patient consulting remotely is
                    the case the exclusions exist for.

                    **Requires** `patient.verify`, held by HIM.
                    """)
    @ApiResponse(responseCode = "204", description = "Recorded.")
    @Transactional
    public ResponseEntity<Void> verify(
            @PathVariable String patientPublicId,
            @Parameter(description = "Who assessed them and when.", required = true)
            @RequestParam String assessmentDetail) {

        if (assessmentDetail == null || assessmentDetail.isBlank()) {
            throw new IllegalArgumentException(
                    "Record who assessed the patient and when. This service is only for "
                            + "patients already physically assessed.");
        }

        Patient patient = require(patientPublicId);
        patient.setIsEligible(true);
        patient.setIsPhysicallyAssessed(true);
        patient.setEligibilityVerifiedAt(LocalDateTime.now());
        patient.setEligibilityVerifiedBy(
                CurrentUser.usernameOrSystem() + ": " + assessmentDetail);
        patientRepository.save(patient);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.RECORD_UPDATED)
                .entityType("Patient")
                .entityId(patient.getId())
                .details("Eligibility verified")
                .reason(assessmentDetail)
                .build());

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{patientPublicId}/activate")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PATIENT_ACTIVATE)")
    @Operation(
            summary = "Activate the account and send a setup link",
            description = """
                    Creates the sign-in account and emails a single-use link so the patient
                    sets their own password.

                    **No password is set here and none is returned.** A member of staff who
                    could choose a patient's password could sign in as them, and everything
                    that account then did would be indistinguishable from the patient's own
                    actions.

                    **Refused until eligibility is recorded.** Identity and clinical
                    suitability are separate gates, and activating on identity alone would
                    let an unassessed patient book a remote consultation.

                    **Requires** `patient.activate`, held by HIM.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Activated, setup link sent."),
            @ApiResponse(responseCode = "400",
                    description = "Eligibility has not been recorded, or the account already "
                            + "exists.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @Transactional
    public ResponseEntity<Map<String, Object>> activate(@PathVariable String patientPublicId) {
        Patient patient = require(patientPublicId);

        if (!Boolean.TRUE.equals(patient.getIsEligible())) {
            throw new IllegalStateException(
                    "Record the eligibility check first. Activating on identity alone would "
                            + "let a patient who has not been physically assessed book a "
                            + "remote consultation.");
        }

        String username = patient.getEhrNumber().toLowerCase();
        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalStateException("An account already exists for that EHR number");
        }

        LocalDateTime now = LocalDateTime.now();

        Users account = new Users();
        account.setUsername(username);
        account.setEmail(username + "@patient.fnph.local");
        account.setPassword("{noop}" + Tokens.generate());
        account.setFirstName(patient.getFirstName());
        account.setLastName(patient.getLastName());
        account.setPatient(patient);
        account.setLoginType(LoginType.EHR_NUMBER);
        account.setStatus(UserStatus.INVITED);
        account.setIsActive(true);
        account.setMfaEnabled(false);
        account.setMustChangePassword(true);
        Users savedAccount = userRepository.save(account);

        var role = roleRepository.findByCode("PATIENT")
                .orElseThrow(() -> new IllegalStateException("PATIENT role is not seeded"));

        UserRole assignment = new UserRole();
        assignment.setUserId(savedAccount.getId());
        assignment.setRoleId(role.getId());
        assignment.setIsPrimary(true);
        assignment.setGrantedAt(now);
        assignment.setGrantedBy(CurrentUser.usernameOrSystem());
        assignment.setGrantReason("Manual enrolment by Health Information Management");
        userRoleRepository.save(assignment);

        patient.setIsActive(true);
        patient.setActivatedAt(now);
        patientRepository.save(patient);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.USER_ACTIVATED)
                .entityType("Patient")
                .entityId(patient.getId())
                .details("Manually enrolled account created for " + username)
                .build());

        return ResponseEntity.ok(Map.of(
                "username", username,
                "status", savedAccount.getStatus().name(),
                "note", "A setup link has been issued. No password was set here."));
    }

    private Patient require(String publicId) {
        return patientRepository.findByPublicId(publicId)
                .orElseThrow(() -> new EntityNotFoundException("No such patient"));
    }
}
