package com.fnph.telepsychiatric.audit.api;

import com.fnph.telepsychiatric.audit.AuditChainVerifier;
import com.fnph.telepsychiatric.audit.AuditLog;
import com.fnph.telepsychiatric.audit.AuditRepository;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/audit")
@RequiredArgsConstructor
@Tag(name = "Administration — Audit")
public class AuditController {

    private final AuditRepository auditRepository;
    private final AuditChainVerifier chainVerifier;

    @GetMapping
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).AUDIT_READ)")
    @Operation(
            summary = "Search the audit trail",
            description = """
                    Filter by action, entity type, user, centre and date range. Newest first.

                    Each entry carries **two identities where they differ**: `username` is
                    the account that authenticated, and `effectivePrincipal` is the account
                    being acted as during a supervised session. "Who did this" has two
                    correct answers in that case and an investigation needs both.

                    Entries are never edited or removed. The application account has UPDATE
                    and DELETE revoked on this table at the database level, and each row
                    carries a hash covering its own content plus the previous row's, so
                    alteration by anyone who got past the grants is detectable. Use
                    `/verify` to check.

                    **Requires** `audit.read`, held only by the Central Administrator.
                    """)
    @ApiResponse(responseCode = "200", description = "Matching entries, newest first.")
    public ResponseEntity<Page<AuditEntryResponse>> search(
            @Parameter(description = "Filter by action.", example = "VIEW_AS_STARTED")
            @RequestParam(required = false) String action,
            @Parameter(description = "Filter by entity type.", example = "Prescription")
            @RequestParam(required = false) String entityType,
            @Parameter(description = "Filter by the account that authenticated.")
            @RequestParam(required = false) Long userId,
            @Parameter(description = "Filter by centre.")
            @RequestParam(required = false) Long centreId,
            @Parameter(description = "From, inclusive (UTC). Defaults to 30 days ago.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "To, inclusive (UTC). Defaults to now.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        LocalDateTime start = from == null ? LocalDateTime.now().minusDays(30) : from;
        LocalDateTime end = to == null ? LocalDateTime.now() : to;

        return ResponseEntity.ok(auditRepository
                .search(action, entityType, userId, centreId, start, end,
                        PageRequest.of(page, Math.min(size, 200)))
                .map(this::toResponse));
    }

    @GetMapping("/supervision/{viewAsSessionId}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).SUPERVISION_READ_LOG)")
    @Operation(
            summary = "Everything done inside one supervised session",
            description = """
                    The question an auditor actually asks is not "what did this
                    administrator do" but "what did they do while acting as someone else".
                    This answers it.

                    Supervised access is read-only, so these entries should all be reads. An
                    entry here recording a state change means the read-only enforcement has
                    a hole in it, and finding one is worth an incident.

                    **Requires** `supervision.read_log`.
                    """)
    @ApiResponse(responseCode = "200", description = "Entries in order, oldest first.")
    public ResponseEntity<List<AuditEntryResponse>> supervisionTrail(
            @PathVariable Long viewAsSessionId) {
        return ResponseEntity.ok(
                auditRepository.findAllByViewAsSessionIdOrderByPerformedAtAsc(viewAsSessionId)
                        .stream().map(this::toResponse).toList());
    }

    @GetMapping("/verify")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).AUDIT_EXPORT)")
    @Operation(
            summary = "Verify the audit chain is intact",
            description = """
                    Walks every entry and recomputes its hash, reporting any break.

                    This is the evidence behind the claim that the audit trail cannot be
                    altered. Two guarantees sit under it: database grants stop the
                    application changing the table at all, and the hash chain detects
                    changes made by anyone who got past the grants, which in practice means
                    someone holding database credentials. Neither is sufficient alone.

                    Tamper-evident rather than tamper-proof. That is the honest guarantee,
                    and it is the achievable one.

                    A break names the entry and says whether a row was removed, inserted,
                    or edited in place. **Any break is an incident**, not a warning.

                    Run this as part of assurance testing and on a schedule.

                    **Requires** `audit.export`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Verification complete. Check `intact` and read `breaks` if false."),
            @ApiResponse(responseCode = "403", description = "Lacking `audit.export`.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<AuditChainVerifier.VerificationResult> verifyChain() {
        return ResponseEntity.ok(chainVerifier.verify());
    }

    private AuditEntryResponse toResponse(AuditLog entry) {
        return new AuditEntryResponse(
                entry.getPublicId(),
                entry.getUsername(),
                entry.getEffectivePrincipal() == null ? null
                        : entry.getEffectivePrincipal().getUsername(),
                entry.getViewAsSessionId(),
                entry.getAction(),
                entry.getEntityType(),
                entry.getEntityId(),
                entry.getDetails(),
                entry.getReason(),
                entry.getOutcome(),
                entry.getCentreId(),
                entry.getIpAddress(),
                entry.getPerformedAt(),
                Boolean.TRUE.equals(entry.getIsSystem()));
    }
}
