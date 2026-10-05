package com.fnph.telepsychiatric.ehr.api;

import com.fnph.telepsychiatric.ehr.*;
import com.fnph.telepsychiatric.handler.ErrorResponse;
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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/verification-requests")
@RequiredArgsConstructor
@Tag(name = "Administration — Patient Verification Queue")
public class VerificationQueueController {

    private final PatientVerificationRequestRepository requestRepository;
    private final PatientRepository patientRepository;
    private final ManualEhrRecordService manualEhrRecordService;

    @GetMapping
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).EHR_VERIFICATION_RESOLVE)")
    @Operation(
            summary = "Patients the snapshot could not match",
            description = """
                    Requests from people who could not enrol automatically, oldest first.

                    **This queue exists because the snapshot is a snapshot.** A patient
                    registered last week is not in an extract taken last month, and refusing
                    them with no route forward would send them back for a physical visit
                    over an administrative gap.

                    **A request is a request, not a grant.** Nothing here created an account,
                    and a request quoting an EHR number that does not exist looks exactly
                    like one quoting a number that does. Verify the person by other means
                    before resolving.

                    A growing queue usually means the active snapshot is stale rather than
                    that patients are getting it wrong. Check `ageInDays` on the active
                    import.

                    **Requires** `ehr_verification.resolve`.
                    """)
    @ApiResponse(responseCode = "200", description = "Requests returned.")
    public ResponseEntity<List<Map<String, Object>>> queue(
            @Parameter(description = "Filter by status. Omit for everything open.",
                    example = "SUBMITTED")
            @RequestParam(required = false) VerificationRequestStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        var pageable = PageRequest.of(page, Math.min(size, 200));
        var results = status == null
                ? requestRepository.findAllByOrderByCreatedAtDesc(pageable)
                : requestRepository.findAllByStatusOrderByCreatedAtAsc(status, pageable);

        return ResponseEntity.ok(results.map(r -> {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("publicId", r.getPublicId());
            row.put("ehrNumberClaimed", r.getEhrNumberClaimed());
            row.put("fullName", r.getFullName());
            row.put("dateOfBirth", r.getDateOfBirth());
            row.put("phoneNumber", r.getPhoneNumber());
            row.put("preferredContact", r.getPreferredContact());
            row.put("supportingNote", r.getSupportingNote());
            row.put("status", r.getStatus().name());
            row.put("submittedAt", r.getCreatedAt());
            return row;
        }).getContent());
    }

    @PostMapping("/{requestPublicId}/assign")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).EHR_VERIFICATION_RESOLVE)")
    @Operation(
            summary = "Move a request to HIM or ICT",
            description = """
                    `WITH_HIM` for a records question: the patient is real and the snapshot
                    is behind. `WITH_ICT` for a technical one: the import failed or the
                    matching is misbehaving.

                    The distinction matters because they have different fixes. A records gap
                    is closed by a fresh snapshot; a technical fault is not.

                    **Requires** `ehr_verification.resolve`.
                    """)
    @ApiResponse(responseCode = "204", description = "Reassigned.")
    @Transactional
    public ResponseEntity<Void> assign(@PathVariable String requestPublicId,
                                       @RequestParam VerificationRequestStatus status) {
        PatientVerificationRequest request = require(requestPublicId);
        request.setStatus(status);
        requestRepository.save(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{requestPublicId}/resolve")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).EHR_VERIFICATION_RESOLVE)")
    @Operation(
            summary = "Close a request",
            description = """
                    Records the outcome and how the person was verified.

                    **RESOLVED creates or updates the patient's manual EHR record**, so they
                    can enrol with their EHR number straight away. It does not create the
                    account: the patient still chooses their own password at enrolment.
                    Resolving the same request again refreshes that record. REJECTED
                    changes nothing.

                    If the patient left date of birth blank, pass `dateOfBirth` from the
                    hospital file, or the request stays open.

                    The notes are required and should say **how** the person was verified,
                    not that they were. "Confirmed" tells a later reader nothing.

                    **Requires** `ehr_verification.resolve`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Closed."),
            @ApiResponse(responseCode = "400", description = "No notes given, or the "
                    + "request lacks the details a manual EHR record needs.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @Transactional
    public ResponseEntity<Void> resolve(
            @PathVariable String requestPublicId,
            @Parameter(description = "RESOLVED or REJECTED.", required = true)
            @RequestParam VerificationRequestStatus outcome,
            @Parameter(description = "How the person was verified, or why the claim could "
                    + "not be substantiated.", required = true)
            @RequestParam String notes,
            @Parameter(description = "The patient record, if one now exists for them.")
            @RequestParam(required = false) String patientPublicId,
            @Parameter(description = "Date of birth from the hospital file. Needed only "
                    + "when the patient left it blank on the help form.", example = "1988-04-12")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateOfBirth) {

        if (notes == null || notes.isBlank()) {
            throw new IllegalArgumentException(
                    "Say how this person was verified. \"Confirmed\" tells a later reader "
                            + "nothing, and this queue is the audit trail for manual identity "
                            + "decisions.");
        }
        if (outcome != VerificationRequestStatus.RESOLVED
                && outcome != VerificationRequestStatus.REJECTED) {
            throw new IllegalArgumentException("The outcome must be RESOLVED or REJECTED");
        }

        PatientVerificationRequest request = require(requestPublicId);

        // A verified patient must be able to enrol straight away. Done first:
        // if the record cannot be written, the request is not marked resolved.
        if (outcome == VerificationRequestStatus.RESOLVED) {
            manualEhrRecordService.upsertFromVerifiedRequest(request, dateOfBirth, notes);
        }

        request.setStatus(outcome);
        request.setResolutionNotes(notes);
        request.setResolvedAt(LocalDateTime.now());
        request.setResolvedBy(CurrentUser.usernameOrSystem());

        if (patientPublicId != null && !patientPublicId.isBlank()) {
            patientRepository.findByPublicId(patientPublicId)
                    .ifPresent(request::setResultingPatient);
        }
        requestRepository.save(request);
        return ResponseEntity.noContent().build();
    }

    private PatientVerificationRequest require(String publicId) {
        return requestRepository.findByPublicId(publicId)
                .orElseThrow(() -> new EntityNotFoundException("No such request"));
    }
}