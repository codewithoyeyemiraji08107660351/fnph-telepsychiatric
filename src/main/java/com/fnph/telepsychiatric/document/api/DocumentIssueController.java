package com.fnph.telepsychiatric.document.api;

import com.fnph.telepsychiatric.document.*;
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
    public ResponseEntity<Map<String, Object>> reissue(
            @Parameter(description = "The issue number printed on the document.", required = true)
            @RequestParam String issueNumber) {

        var document = documentService.forPatient(null).stream()
                .filter(d -> issueNumber.equals(d.getIssueNumber()))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException(
                        "No document with issue number " + issueNumber));

        if (document.getStoragePath() != null) {
            throw new IllegalStateException(
                    "That document already has a file. Re-rendering would replace a file the "
                            + "patient may already have downloaded, with no way to tell the "
                            + "two apart.");
        }

        return ResponseEntity.ok(Map.of(
                "issueNumber", document.getIssueNumber(),
                "status", document.effectiveStatus(java.time.LocalDateTime.now()).name(),
                "downloadCount", document.getDownloadCount(),
                "maxDownloads", document.getMaxDownloads(),
                "note", "Allowance and dates unchanged"));
    }
}
