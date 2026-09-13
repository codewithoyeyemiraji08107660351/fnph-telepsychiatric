package com.fnph.telepsychiatric.patient.api;

import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.patient.Patient;
import com.fnph.telepsychiatric.patient.PatientRepository;
import com.fnph.telepsychiatric.security.CurrentUser;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/patients")
@RequiredArgsConstructor
@Tag(name = "Patient Records")
public class PatientRecordController {

    private final PatientRepository patientRepository;

    @GetMapping("/me")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PATIENT_READ_OWN)")
    @Operation(
            summary = "My patient record",
            description = """
                    What the hospital holds about you in this system.

                    **This is not your hospital record.** The offline FNPH EHR stays
                    authoritative and is not retrieved here. What you see is the account
                    created from a snapshot, plus what has happened in this service.

                    `driftFlagged` means a later snapshot carried different details for you.
                    Your account was **not** changed automatically, deliberately, and Health
                    Information Management is reviewing it. Show it, because a patient whose
                    phone number changed needs to know the hospital has noticed.

                    **Requires** `patient.read_own`.
                    """)
    @ApiResponse(responseCode = "200", description = "Record returned.")
    public ResponseEntity<Map<String, Object>> me() {
        Patient patient = patientRepository.findById(CurrentUser.require().getPatientId())
                .orElseThrow(() -> new EntityNotFoundException("No patient record on this account"));
        return ResponseEntity.ok(toResponse(patient, true));
    }

    @GetMapping
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PATIENT_READ)")
    @Operation(
            summary = "Search patients",
            description = """
                    For clinical and records staff.

                    **No centre role holds `patient.read`.** A centre patient is never an
                    FNPH patient, and a centre reaching this list would collapse the
                    separation the whole tenancy design exists to keep.

                    **Requires** `patient.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Patients returned.")
    public ResponseEntity<List<Map<String, Object>>> search(
            @Parameter(description = "EHR number or part of a name.")
            @RequestParam(required = false) String term,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        var pageable = PageRequest.of(page, Math.min(size, 200));

        List<Patient> results = (term == null || term.isBlank())
                ? patientRepository.findAll(pageable).getContent()
                : patientRepository.search(term.trim(), pageable);

        return ResponseEntity.ok(results.stream().map(p -> toResponse(p, false)).toList());
    }

    @GetMapping("/{patientPublicId}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PATIENT_READ)")
    @Operation(
            summary = "One patient record",
            description = """
                    The clinician's view before a consultation.

                    Every read of a patient record is audited by patient. That is the point
                    of a records permission rather than an open list: a later question about
                    who looked at whose record has an answer.

                    **Requires** `patient.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Record returned.")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String patientPublicId) {
        return ResponseEntity.ok(toResponse(require(patientPublicId), false));
    }

    @PutMapping("/{patientPublicId}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PATIENT_UPDATE)")
    @Operation(
            summary = "Correct a patient's contact details",
            description = """
                    Phone, email and address only.

                    **The EHR number and name are not editable here.** Those come from the
                    hospital record, and correcting them in this system without correcting
                    the offline record would create two versions of the truth with no way to
                    tell which is current. A name change goes through HIM and the next
                    snapshot.

                    **Requires** `patient.update`.
                    """)
    @ApiResponse(responseCode = "200", description = "Updated.")
    @Transactional
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable String patientPublicId,
            @RequestParam(required = false) String phoneNumber,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String address) {

        Patient patient = require(patientPublicId);
        if (phoneNumber != null) {
            patient.setPhoneNumber(phoneNumber);
        }
        if (email != null) {
            patient.setEmail(email);
        }
        if (address != null) {
            patient.setAddress(address);
        }
        patientRepository.save(patient);
        return ResponseEntity.ok(Map.of("updated", true));
    }

    @GetMapping("/drift")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PATIENT_FLAG_DRIFT)")
    @Operation(
            summary = "Accounts whose details changed in a later snapshot",
            description = """
                    HIM's review queue.

                    When a new EHR snapshot carries a different name or phone for an already
                    active account, the account is **flagged and not updated**. Silently
                    rebinding an active account to changed contact details is an
                    account-takeover path: one wrong or malicious row in an import would
                    redirect that patient's verification codes.

                    Work through these by hand and confirm the person before clearing.

                    **Requires** `patient.flag_drift`, held by HIM.
                    """)
    @ApiResponse(responseCode = "200", description = "Flagged accounts returned.")
    public ResponseEntity<List<Map<String, Object>>> drift(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        return ResponseEntity.ok(patientRepository
                .findAllByDriftFlaggedTrueOrderByDriftFlaggedAtDesc(
                        PageRequest.of(page, Math.min(size, 200)))
                .map(p -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("publicId", p.getPublicId());
                    row.put("ehrNumber", p.getEhrNumber());
                    row.put("name", p.getFirstName() + " " + p.getLastName());
                    row.put("flaggedAt", p.getDriftFlaggedAt());
                    row.put("details", p.getDriftDetails());
                    return row;
                }).getContent());
    }

    @PostMapping("/{patientPublicId}/drift/clear")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PATIENT_FLAG_DRIFT)")
    @Operation(
            summary = "Clear a drift flag after checking",
            description = """
                    Records that HIM confirmed the person and resolved the difference.

                    The notes are required and should say **how** they were confirmed.
                    "Checked" tells a later reader nothing, and this is the audit trail for
                    a manual identity decision.

                    **Requires** `patient.flag_drift`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Cleared."),
            @ApiResponse(responseCode = "400", description = "No notes given.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @Transactional
    public ResponseEntity<Void> clearDrift(@PathVariable String patientPublicId,
                                           @RequestParam String notes) {
        if (notes == null || notes.isBlank()) {
            throw new IllegalArgumentException(
                    "Say how the patient was confirmed. This is the audit trail for a "
                            + "manual identity decision.");
        }
        Patient patient = require(patientPublicId);
        patient.setDriftFlagged(false);
        patient.setDriftDetails("Resolved: " + notes);
        patientRepository.save(patient);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{patientPublicId}/deactivate")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PATIENT_UPDATE)")
    @Operation(
            summary = "Deactivate a patient account",
            description = """
                    Stops the account being used. The record and its history stay.

                    Not a delete. A consultation happened, documents were issued, money
                    moved. Removing the patient row would orphan all of it.

                    **Requires** `patient.update`.
                    """)
    @ApiResponse(responseCode = "204", description = "Deactivated.")
    @Transactional
    public ResponseEntity<Void> deactivate(@PathVariable String patientPublicId,
                                           @RequestParam String reason) {
        Patient patient = require(patientPublicId);
        patient.setIsActive(false);
        patientRepository.save(patient);
        return ResponseEntity.noContent().build();
    }

    private Patient require(String publicId) {
        return patientRepository.findByPublicId(publicId)
                .orElseThrow(() -> new EntityNotFoundException("No such patient"));
    }

    private Map<String, Object> toResponse(Patient p, boolean ownView) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("publicId", p.getPublicId());
        row.put("ehrNumber", p.getEhrNumber());
        row.put("firstName", p.getFirstName());
        row.put("lastName", p.getLastName());
        row.put("phoneNumber", p.getPhoneNumber());
        row.put("email", p.getEmail());
        row.put("isActive", p.getIsActive());
        row.put("activatedAt", p.getActivatedAt());
        row.put("driftFlagged", p.getDriftFlagged());
        if (!ownView) {
            // Staff see the verification provenance; a patient does not need it.
            row.put("eligibilityVerifiedBy", p.getEligibilityVerifiedBy());
            row.put("eligibilityVerifiedAt", p.getEligibilityVerifiedAt());
            row.put("driftDetails", p.getDriftDetails());
        }
        return row;
    }
}
