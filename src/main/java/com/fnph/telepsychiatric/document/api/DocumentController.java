package com.fnph.telepsychiatric.document.api;

import com.fnph.telepsychiatric.document.*;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "Clinical Documents")
public class DocumentController {

    private final IssuedDocumentService documentService;
    private final QrCodeGenerator qrCodeGenerator;

    @GetMapping("/api/v1/documents/mine")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).DOCUMENT_READ_OWN)")
    @Operation(
            summary = "My documents",
            description = """
                    Every document released to this patient, newest first.

                    **Show expired and view-only documents rather than hiding them.** A
                    patient whose prescription has expired needs to see that it expired,
                    not find it missing and assume the system lost it.

                    `viewOnly` means the download allowance is used. The document is still
                    readable on screen until it expires; only the file is refused.

                    **Requires** `document.read_own`.
                    """)
    @ApiResponse(responseCode = "200", description = "Documents returned.",
            content = @Content(schema = @Schema(implementation = DocumentResponse.class)))
    public ResponseEntity<List<DocumentResponse>> mine() {
        return ResponseEntity.ok(
                documentService.forPatient(CurrentUser.require().getPatientId())
                        .stream().map(this::toResponse).toList());
    }

    @PostMapping("/api/v1/documents/{documentPublicId}/download")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).DOCUMENT_DOWNLOAD)")
    @Operation(
            summary = "Download a document",
            description = """
                    Claims one download against the allowance.

                    **A prescription allows one successful download, then becomes
                    view-only.** The document stays readable on screen until it expires.

                    The check and the increment happen in one statement, so a retry on a
                    poor connection cannot burn the allowance twice and two devices
                    requesting at once cannot both succeed against a limit of one.

                    Refused with a specific reason when the allowance is used, the document
                    has expired, or it was withdrawn. Every attempt is recorded, so a
                    patient saying "it counted a download I never received" can be checked.

                    **Requires** `document.download`, and the document must be yours.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Download claimed.",
                    content = @Content(schema = @Schema(implementation = DocumentResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Allowance used, expired, or withdrawn. The message says which.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404",
                    description = "No such document. Deliberately indistinguishable from one "
                            + "belonging to someone else.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<DocumentResponse> download(
            @PathVariable String documentPublicId, HttpServletRequest http) {
        return ResponseEntity.ok(toResponse(documentService.claimDownload(
                documentPublicId, clientIp(http), http.getHeader("User-Agent"))));
    }

    @GetMapping(value = "/api/v1/documents/{documentPublicId}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).DOCUMENT_READ_OWN)")
    @Operation(
            summary = "QR code for a document",
            description = """
                    A PNG of the verification URL, generated on demand.

                    Not stored: the image is only a rendering of the URL and regenerating it
                    costs nothing, while a base64 copy per document is dead weight.

                    Error correction is high because these get printed, folded and carried
                    to a pharmacy. A code that stops scanning after a crease sends the
                    patient back to the hospital.

                    **Viewing the QR does not consume a download.**

                    **Requires** `document.read_own`.
                    """)
    @ApiResponse(responseCode = "200", description = "PNG image.")
    public ResponseEntity<byte[]> qrCode(@PathVariable String documentPublicId) {
        var document = documentService.forPatient(CurrentUser.require().getPatientId()).stream()
                .filter(d -> d.getPublicId().equals(documentPublicId))
                .findFirst()
                .orElseThrow(() -> new IssuedDocumentService.DocumentException("No such document"));

        return ResponseEntity.ok(qrCodeGenerator.generate(
                documentService.verificationUrlFor(document.getId())));
    }

    @GetMapping("/api/v1/verify/{token}")
    @SecurityRequirements
    @Operation(
            summary = "Verify a document from its QR code",
            description = """
                    Confirms a document was issued by FNPH Kaduna and says whether it is
                    still valid.

                    **Public and unauthenticated.** A pharmacist checking a prescription has
                    no account here, and requiring one would make the QR code useless.

                    **It returns almost nothing.** The issue number, the type, the dates and
                    a status. No patient name, no clinician name, no medication, no
                    diagnosis. The person scanning could be anyone who found a piece of
                    paper, and the document in their hand already carries what they
                    legitimately need. This only confirms it was not forged.

                    **A saved copy verifies as expired.** The QR encodes a token that
                    resolves here rather than a self-contained payload, which would keep
                    saying "valid" forever on a photograph taken the day it was issued.

                    `REVOKED` means the hospital withdrew it and it must not be acted on.
                    `NOT_FOUND` is also what a guessed code returns; attempts are recorded.

                    Compare `issueNumber` against the number printed on the document. A
                    genuine QR pointing at a different number is a forged document.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Verification result. Read `status`; a 200 does not mean valid.",
                    content = @Content(schema = @Schema(implementation = VerificationResponse.class)))
    })
    public ResponseEntity<VerificationResponse> verify(
            @Parameter(description = "The token from the QR code.", required = true)
            @PathVariable String token,
            HttpServletRequest http) {

        var result = documentService.verify(token, clientIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.ok(new VerificationResponse(
                result.status(), result.issueNumber(), result.documentType(),
                result.issuedOn(), result.expiresOn()));
    }

    @PostMapping("/api/v1/documents/{documentPublicId}/revoke")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).DOCUMENT_REVOKE)")
    @Operation(
            summary = "Withdraw a document",
            description = """
                    Marks a document revoked. It stops downloading immediately, and **a
                    saved or printed copy now verifies as REVOKED**.

                    That is the point of server-resolved verification: a patient carrying a
                    printed page finds out at the pharmacy counter that it was withdrawn,
                    rather than the pharmacy dispensing against it.

                    Use this for a document issued in error. A clinical correction is a new
                    document that supersedes this one, authored by the doctor.

                    **Requires** `document.revoke`.
                    """)
    @ApiResponse(responseCode = "200", description = "Withdrawn.")
    public ResponseEntity<DocumentResponse> revoke(
            @PathVariable String documentPublicId,
            @Parameter(description = "Why. Recorded and shown to staff.", required = true)
            @RequestParam String reason) {
        return ResponseEntity.ok(toResponse(documentService.revoke(documentPublicId, reason)));
    }

    private DocumentResponse toResponse(IssuedDocument d) {
        return new DocumentResponse(
                d.getPublicId(), d.getIssueNumber(), d.getDocumentType().name(),
                d.effectiveStatus(java.time.LocalDateTime.now()).name(),
                d.getIssuedAt(), d.getExpiresAt(),
                d.getDownloadCount(), d.getMaxDownloads(),
                Boolean.TRUE.equals(d.getIsViewOnly()),
                documentService.verificationUrlFor(d.getId()),
                d.getRevokedReason());
    }

    private String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        return (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim() : http.getRemoteAddr();
    }
}
