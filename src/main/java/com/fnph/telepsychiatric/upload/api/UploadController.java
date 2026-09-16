package com.fnph.telepsychiatric.upload.api;

import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.upload.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/uploads")
@RequiredArgsConstructor
@Tag(name = "Uploads")
public class UploadController {

    private final UploadService uploadService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).UPLOAD_CREATE)")
    @Operation(
            summary = "Upload a supporting file",
            description = """
                    Prior laboratory results, referral attachments, anything the clinician
                    should see before the consultation.

                    **The filename is never used as a path.** It is kept for display and the
                    storage path is generated server-side, because a filename is user input
                    and user input in a path is how a directory traversal happens.

                    **The content type is checked against an allow-list**, not a block-list.
                    A block-list is a list of the file types somebody thought of.

                    Size is enforced while streaming, not from a header, because a client
                    can claim any size it likes.

                    **Files are not virus scanned yet.** `scanStatus` returns `UPLOADED` and
                    nothing moves it. Until a scanner is wired, treat anything uploaded here
                    as untrusted and do not open it outside the application.

                    **Requires** `upload.create`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Stored.",
                    content = @Content(schema = @Schema(implementation = UploadResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Empty file, over the size limit, or a type that is not "
                            + "accepted.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<UploadResponse> upload(
            @Parameter(description = "The file.", required = true)
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "What it is.", example = "LABORATORY_RESULT", required = true)
            @RequestParam FileCategory category,
            @Parameter(description = "What the clinician should know about it.")
            @RequestParam(required = false) String description,
            @Parameter(description = "The appointment or referral it belongs to.")
            @RequestParam(required = false) String referenceId) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(toResponse(uploadService.upload(file, category, description, referenceId)));
    }

    @GetMapping
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).UPLOAD_READ)")
    @Operation(summary = "Files attached to an appointment or referral",
            description = "Staff had no way to find what a patient attached; files could only be "
                    + "opened by an id nobody could list.")
    public ResponseEntity<List<UploadResponse>> forReference(@RequestParam String referenceId) {
        return ResponseEntity.ok(uploadService.forReference(referenceId).stream().map(this::toResponse).toList());
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).UPLOAD_READ_OWN)")
    @Operation(summary = "Files I uploaded",
            description = "Newest first. **Requires** `upload.read_own`.")
    @ApiResponse(responseCode = "200", description = "Files returned.")
    public ResponseEntity<List<UploadResponse>> mine() {
        return ResponseEntity.ok(uploadService.mine().stream().map(this::toResponse).toList());
    }

    @GetMapping("/{uploadPublicId}/content")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).UPLOAD_READ)")
    @Operation(
            summary = "Download an uploaded file",
            description = """
                    Streams the bytes.

                    **The checksum is verified as it is read.** A file truncated by a crash
                    mid-write or corrupted by failing hardware fails here rather than being
                    served, because the row pointing at it would look perfectly healthy
                    either way.

                    Content type comes from the database, not the extension on disk. A file
                    renamed to `.pdf` does not become a PDF.

                    **Requires** `upload.read`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The file."),
            @ApiResponse(responseCode = "400",
                    description = "Missing on disk, or it failed its integrity check.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<InputStreamResource> content(@PathVariable String uploadPublicId) {
        UploadService.FileContent content = uploadService.read(uploadPublicId);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.sizeBytes())
                // Attachment, always. Inline lets a crafted SVG or HTML file run
                // script in the application's origin.
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + content.filename() + "\"")
                .body(new InputStreamResource(content.stream()));
    }

    @DeleteMapping("/{uploadPublicId}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).UPLOAD_DELETE)")
    @Operation(
            summary = "Remove an uploaded file",
            description = """
                    Marks the record deleted and queues the file for removal.

                    **The file is not unlinked immediately.** Doing it inside this
                    transaction would lose it if the transaction rolled back, and a disk has
                    no undo. A sweeper removes it once the grace period has passed, which is
                    also a window in which a mistake can still be reversed.

                    **Requires** `upload.delete`.
                    """)
    @ApiResponse(responseCode = "204", description = "Queued for removal.")
    public ResponseEntity<Void> delete(@PathVariable String uploadPublicId,
                                       @RequestParam String reason) {
        uploadService.delete(uploadPublicId, reason);
        return ResponseEntity.noContent().build();
    }

    private UploadResponse toResponse(FileUpload u) {
        return new UploadResponse(
                u.getPublicId(), u.getOriginalFileName(), u.getMimeType(), u.getFileSize(),
                u.getCategory().name(), u.getScanStatus().name(), u.getDescription(),
                u.getUploadedBy(), u.getUploadedAt());
    }

    @Schema(name = "Upload", description = "A stored file.")
    public record UploadResponse(
            String publicId,
            @Schema(description = "As the uploader named it. Never used as a path.",
                    example = "lab-results-june.pdf")
            String originalFileName,
            @Schema(example = "application/pdf") String contentType,
            @Schema(example = "184320") Long sizeBytes,
            @Schema(example = "LABORATORY_RESULT") String category,
            @Schema(description = """
                    **Always `UPLOADED` today.** No virus scanner is wired, so nothing moves \
                    this. Treat uploaded files as untrusted.
                    """,
                    example = "UPLOADED")
            String scanStatus,
            @Schema(nullable = true) String description,
            @Schema(nullable = true) String uploadedBy,
            LocalDateTime uploadedAt
    ) {
    }
}
