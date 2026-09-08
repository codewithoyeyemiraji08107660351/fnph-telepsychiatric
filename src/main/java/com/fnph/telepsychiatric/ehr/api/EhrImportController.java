package com.fnph.telepsychiatric.ehr.api;

import com.fnph.telepsychiatric.ehr.EhrImportService;
import com.fnph.telepsychiatric.ehr.EhrVerificationImport;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/ehr-imports")
@RequiredArgsConstructor
@Tag(name = "Administration — EHR Verification Source")
public class EhrImportController {

    private final EhrImportService importService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).EHR_IMPORT_UPLOAD)")
    @Operation(
            summary = "Upload a snapshot of the offline EHR",
            description = """
                    Parses and validates a CSV export. **Does not activate it**; that is a
                    separate, deliberate step.

                    Required columns: `ehr_number`, `full_name`, `date_of_birth`,
                    `phone_number`. Optional: `clinic`, `patient_status`. Dates may be
                    `YYYY-MM-DD`, `DD/MM/YYYY` or `DD-MM-YYYY`.

                    **A single bad row rejects the whole file.** A partial import leaves
                    half a patient list loaded with no way to tell which half, and it
                    surfaces later as a patient who cannot enrol for no visible reason.
                    `validationReport` names every failing line.

                    `sourceAsAt` is the date the hospital **extracted** the file, not
                    today. Every screen relying on this snapshot shows its age, and getting
                    this wrong makes stale data look current.

                    Phone numbers are normalised to their last nine digits, so the same
                    number written `08012345678`, `+2348012345678` and `2348012345678`
                    matches. Date of birth and phone are stored hashed with masked display
                    forms only; the plaintext is never persisted.

                    Uploading the same file twice is refused by checksum.

                    **Requires** `ehr_import.upload`, held by HIM and ICT Support.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Parsed. Check `status`: VALIDATED can be activated, REJECTED "
                            + "means nothing was loaded.",
                    content = @Content(schema = @Schema(implementation = EhrImportResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Empty file, duplicate checksum, or a future extract date.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Lacking `ehr_import.upload`.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<EhrImportResponse> upload(
            @Parameter(description = "The CSV export.", required = true)
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "The date the hospital extracted this file.",
                    example = "2026-09-01", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate sourceAsAt) {
        return ResponseEntity.ok(toResponse(importService.upload(file, sourceAsAt)));
    }

    @PostMapping("/{importPublicId}/activate")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).EHR_IMPORT_ACTIVATE)")
    @Operation(
            summary = "Make a validated snapshot the one enrolment matches against",
            description = """
                    Activates the snapshot and supersedes the previous one, which is
                    retained for audit and for answering what a record said when an
                    account was activated.

                    **Drift is detected here.** Active accounts whose stored name or phone
                    differs from the new snapshot are flagged for HIM review and **not
                    updated**. Silently rebinding an active account to changed contact
                    details would let a wrong or malicious row redirect a patient's
                    verification codes, which is an account-takeover path. The response
                    reports how many were flagged.

                    Only a VALIDATED import can be activated.

                    **Requires** `ehr_import.activate`, held by HIM and the Central
                    Administrator. ICT Support can upload but not activate: deciding which
                    snapshot the hospital enrols patients against is a records decision,
                    not a technical one.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Activated. Enrolment now matches "
                    + "against this snapshot.",
                    content = @Content(schema = @Schema(implementation = EhrImportResponse.class))),
            @ApiResponse(responseCode = "400", description = "The import is not in VALIDATED state.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<EhrImportResponse> activate(
            @PathVariable String importPublicId,
            @Parameter(description = "Why this snapshot is being activated. Written to the audit "
                    + "trail.", example = "Monthly refresh, extract taken 1 September 2026")
            @RequestParam String reason) {
        return ResponseEntity.ok(toResponse(importService.activate(importPublicId, reason)));
    }

    @GetMapping
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).EHR_IMPORT_READ)")
    @Operation(
            summary = "Recent imports",
            description = """
                    The last twenty uploads with their status, row counts, validation
                    reports and drift counts.

                    Watch `ageInDays` on the ACTIVE import. A snapshot that keeps ageing is
                    a snapshot nobody is refreshing, and the visible symptom is patients
                    registered recently who cannot enrol and end up in the exception queue.

                    **Requires** `ehr_import.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Imports returned, newest first.")
    public ResponseEntity<List<EhrImportResponse>> recent() {
        return ResponseEntity.ok(importService.recentImports().stream().map(this::toResponse).toList());
    }

    @GetMapping("/active")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).EHR_IMPORT_READ)")
    @Operation(
            summary = "The snapshot enrolment is currently matching against",
            description = """
                    Returns 404 when none has been activated, which means **no patient can
                    enrol**. That is a service-down condition for the patient front door
                    even though every other part of the system is running, so it belongs on
                    the operations dashboard.

                    **Requires** `ehr_import.read`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The active snapshot."),
            @ApiResponse(responseCode = "404",
                    description = "No active snapshot. Patient enrolment is unavailable.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<EhrImportResponse> active() {
        return importService.activeImport()
                .map(i -> ResponseEntity.ok(toResponse(i)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private EhrImportResponse toResponse(EhrVerificationImport i) {
        return new EhrImportResponse(
                i.getPublicId(), i.getFileName(), i.getStatus().name(),
                i.getSourceAsAt(), i.ageInDays(),
                i.getRowCount(), i.getValidRowCount(), i.getRejectedRowCount(),
                i.getValidationReport(), i.getDriftDetectedCount(),
                i.getUploadedBy() == null ? null : i.getUploadedBy().getUsername(),
                i.getUploadedAt(), i.getActivatedAt(), i.getActivatedBy());
    }
}
