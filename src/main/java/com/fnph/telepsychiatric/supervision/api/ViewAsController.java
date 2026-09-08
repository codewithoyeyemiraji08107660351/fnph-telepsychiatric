package com.fnph.telepsychiatric.supervision.api;

import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.supervision.ViewAsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/supervision")
@RequiredArgsConstructor
@Tag(name = "Administration — Supervised Access")
public class ViewAsController {

    private final ViewAsService viewAsService;

    @PostMapping
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).SUPERVISION_VIEW_AS)")
    @Operation(
            summary = "Open a supervised view of another user's dashboard",
            description = """
                    Lets the Central Administrator see exactly what a member of staff sees,
                    which is what makes a support call about "the release button is greyed
                    out" answerable.

                    **Supervised access is read-only.** The response lists the permissions
                    available, and they are the target's non-mutating ones only. The
                    administrator cannot write a clinical note, sign one, issue a
                    prescription, submit a professional review, approve a booking, move
                    money or download a patient's document.

                    That is not caution. A note written this way would be indistinguishable
                    afterwards from one the clinician wrote, and the specification is
                    explicit that clinician-authored content is not altered by anyone else.
                    A supervision feature that can forge clinical authorship is not a
                    supervision feature.

                    `document.download` is excluded on purpose: it consumes the patient's
                    single allowed download, and looking at a dashboard must not burn it.

                    **Patient accounts cannot be supervised.** Reading a patient's record is
                    a records access under `patient.read`, audited by patient. Dressing it
                    up as supervision would hide it inside a mode built for staff dashboards.

                    **One session at a time.** Two open sessions would make the audit trail
                    ambiguous about which one an action belonged to.

                    The session expires automatically, so an administrator who walks away
                    does not leave the mode running. Every action inside it is recorded with
                    both identities: the administrator who authenticated and the account
                    being acted as.

                    **Requires** `supervision.view_as`, held only by the Central
                    Administrator.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Session opened.",
                    content = @Content(schema = @Schema(implementation = ViewAsSessionResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Target is yourself, a patient account, or not active.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409",
                    description = "You already have a supervised session open. End it first.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Lacking `supervision.view_as`.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ViewAsSessionResponse> start(@Valid @RequestBody StartViewAsRequest request,
                                                       HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        String ip = (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim() : http.getRemoteAddr();
        return ResponseEntity.ok(
                viewAsService.start(request, ip, http.getHeader("User-Agent")));
    }

    @DeleteMapping("/{sessionPublicId}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).SUPERVISION_VIEW_AS)")
    @Operation(
            summary = "End a supervised session",
            description = """
                    Closes the session and records how many actions took place inside it.

                    End it as soon as the question is answered rather than letting it
                    expire. An open session is one where anything the administrator does
                    is attributed to two identities, which is correct but harder to read
                    later.

                    **Requires** `supervision.view_as`, and the session must be yours.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Session ended."),
            @ApiResponse(responseCode = "400", description = "That session belongs to someone else.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such session.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> end(
            @Parameter(description = "The session identifier.", required = true)
            @PathVariable String sessionPublicId,
            @RequestParam(required = false) String endReason) {
        viewAsService.end(sessionPublicId, endReason);
        return ResponseEntity.noContent().build();
    }
}
