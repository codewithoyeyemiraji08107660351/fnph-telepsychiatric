package com.fnph.telepsychiatric.document.api;

import com.fnph.telepsychiatric.document.DocumentStatus;
import com.fnph.telepsychiatric.document.IssuedDocument;
import com.fnph.telepsychiatric.document.IssuedDocumentRepository;
import com.fnph.telepsychiatric.document.IssuedDocumentService;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.patient.PatientRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/documents")
@RequiredArgsConstructor
@Tag(name = "Administration — Document Issue")
public class DocumentIssueController {

    private final IssuedDocumentService documentService;
    private final PatientRepository patientRepository;
    private final IssuedDocumentRepository documentRepository;
    private final com.fnph.telepsychiatric.document.render.DocumentRenderer renderer;
    private final com.fnph.telepsychiatric.clinical.PrescriptionRepository prescriptionRepository;
    private final com.fnph.telepsychiatric.clinical.InvestigationRepository investigationRepository;
    private final com.fnph.telepsychiatric.clinical.FollowUpRepository followUpRepository;
    private final com.fnph.telepsychiatric.audit.AuditService auditService;

    @PostMapping("/reissue")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).DOCUMENT_ISSUE)")
    @Operation(
            summary = "Re-render a document whose file is missing",
            description = """
                    For the one case that needs it: a document whose record exists and whose
                    file does not, which after a restore usually means the files were not
                    restored with the database.

                    **This does not create a new document.** The issue number, the validity
                    dates and the download allowance are unchanged, because a patient who
                    has already downloaded once must not get a second allowance by asking
                    support, and a new issue number would not match the paper they may
                    already be holding.

                    Documents are normally issued automatically when the Hub Coordinator
                    releases a bundle. If nothing has been issued at all, the bundle was
                    never released, and this is not the fix.

                    **Requires** `document.issue`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Re-rendered against the same "
                    + "issue number and allowance."),
            @ApiResponse(responseCode = "400",
                    description = "The file is already present, so nothing needed doing.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Map<String, Object>> reissue(
            @Parameter(description = "The issue number printed on the document.", required = true)
            @RequestParam String issueNumber) {

        // Looked up by issue number. The previous lookup searched forPatient(null),
        // which finds nothing, and then returned without rendering anything.
        IssuedDocument document = documentRepository.findByIssueNumber(issueNumber)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No document with issue number " + issueNumber));
        if (document.getStoragePath() != null) {
            throw new IllegalStateException(
                    "That document already has a file. Re-rendering would replace a file the "
                            + "patient may already have downloaded, with no way to tell the "
                            + "two apart.");
        }
        String verificationUrl = documentService.verificationUrlFor(document.getId());
        java.time.LocalDate expiry = document.getExpiresAt() == null ? null : document.getExpiresAt().toLocalDate();
        byte[] pdf = switch (document.getDocumentType()) {
            case PRESCRIPTION -> renderer.renderPrescription(
                    prescriptionRepository.findById(document.getSourceId())
                            .orElseThrow(() -> new EntityNotFoundException("The prescription behind this document is missing")),
                    document.getIssueNumber(), verificationUrl, expiry);
            case INVESTIGATION_REQUEST -> renderer.renderInvestigation(
                    investigationRepository.findById(document.getSourceId())
                            .orElseThrow(() -> new EntityNotFoundException("The request behind this document is missing")),
                    document.getIssueNumber(), verificationUrl, expiry);
            case FOLLOW_UP_RECOMMENDATION -> renderer.renderFollowUp(
                    followUpRepository.findById(document.getSourceId())
                            .orElseThrow(() -> new EntityNotFoundException("The recommendation behind this document is missing")),
                    document.getIssueNumber(), verificationUrl);
            default -> throw new IllegalStateException("This document type cannot be re-rendered here");
        };
        IssuedDocument rendered = documentService.attachRendered(document.getId(), pdf, "application/pdf");
        return ResponseEntity.ok(Map.of(
                "issueNumber", rendered.getIssueNumber(),
                "status", rendered.effectiveStatus(java.time.LocalDateTime.now()).name(),
                "downloadCount", rendered.getDownloadCount(),
                "maxDownloads", rendered.getMaxDownloads(),
                "note", "File rendered. Allowance and dates unchanged."));
    }

    /**
     * One more download for a patient whose download failed.
     *
     * The allowance is counted before the file is sent, so a dropped connection
     * uses the patient's only download with nothing received. FNPH decides
     * whether this is allowed at all; the action is limited to document.issue,
     * needs a reason, adds exactly one, and is audited.
     */
    @PostMapping("/{issueNumber}/allow-download")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).DOCUMENT_ISSUE)")
    @org.springframework.transaction.annotation.Transactional
    @Operation(summary = "Allow one more download of an issued document")
    public ResponseEntity<Map<String, Object>> allowDownload(@PathVariable String issueNumber,
                                                             @RequestParam String reason) {
        if (reason == null || reason.trim().length() < 10) {
            throw new IllegalArgumentException("Say why another download is allowed, in at least 10 characters.");
        }
        IssuedDocument document = documentRepository.findByIssueNumber(issueNumber)
                .orElseThrow(() -> new EntityNotFoundException("No document with issue number " + issueNumber));
        if (document.effectiveStatus(java.time.LocalDateTime.now()) != DocumentStatus.ACTIVE) {
            throw new IllegalStateException("Only a valid document can be downloaded again. This one is "
                    + document.effectiveStatus(java.time.LocalDateTime.now()) + ".");
        }
        if (document.getDownloadCount() < document.getMaxDownloads()) {
            throw new IllegalStateException("The patient still has a download available.");
        }
        document.setMaxDownloads(document.getMaxDownloads() + 1);
        documentRepository.save(document);
        auditService.record(com.fnph.telepsychiatric.audit.AuditService.AuditEvent.builder()
                .action(com.fnph.telepsychiatric.audit.AuditAction.RECORD_UPDATED)
                .entityType("IssuedDocument")
                .entityId(document.getId())
                .details("One more download allowed for " + issueNumber)
                .reason(reason.trim())
                .build());
        return ResponseEntity.ok(Map.of(
                "issueNumber", document.getIssueNumber(),
                "downloadCount", document.getDownloadCount(),
                "maxDownloads", document.getMaxDownloads()));
    }
}
