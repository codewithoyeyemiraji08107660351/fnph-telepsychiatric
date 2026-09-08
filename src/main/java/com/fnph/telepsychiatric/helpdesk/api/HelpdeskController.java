package com.fnph.telepsychiatric.helpdesk.api;

import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.helpdesk.*;
import com.fnph.telepsychiatric.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/support")
@RequiredArgsConstructor
@Tag(name = "Helpdesk")
public class HelpdeskController {

    private final HelpdeskService helpdeskService;
    private final SupportTicketRepository ticketRepository;

    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).TICKET_CREATE)")
    @Operation(
            summary = "Raise a support ticket",
            description = """
                    Creates a ticket and puts it on the helpdesk queue.

                    **`CLINICAL_CONCERN` behaves differently and deliberately so.** It is
                    escalated to the clinical coordination team immediately, an automatic
                    reply with emergency guidance is added before any agent sees it, and
                    the helpdesk cannot answer or close it.

                    A support agent has no clinical permission and no clinical training.
                    Letting them reply would put them in the position of giving medical
                    advice, and raising the priority instead would leave it with them and
                    only make them answer faster.

                    The automatic reply goes out without waiting for an agent, because
                    someone worried about their health at 11pm should not sit in a queue
                    before being told this service is not for emergencies.

                    Related references are identifiers only. An agent can see that a booking
                    exists and where it got to; they cannot follow it into the consultation
                    note or the prescription.

                    **Requires** `ticket.create`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Ticket raised.",
                    content = @Content(schema = @Schema(implementation = TicketResponse.class))),
            @ApiResponse(responseCode = "400", description = "Missing category, subject or body.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<TicketResponse> raise(@Valid @RequestBody RaiseTicketRequest request) {
        SupportTicket ticket = helpdeskService.raise(
                TicketCategory.valueOf(request.category()), request.subject(), request.body(),
                request.appointmentReference(), request.paymentReference(),
                request.documentNumber());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(ticket, false));
    }

    @GetMapping("/tickets/mine")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).TICKET_READ_OWN)")
    @Operation(
            summary = "My tickets",
            description = """
                    Every ticket you raised, with its conversation.

                    **Internal staff notes are not included.** Staff record context for each
                    other without it becoming correspondence; without that distinction they
                    would either write nothing down or write it somewhere outside the
                    system.

                    **Requires** `ticket.read_own`.
                    """)
    @ApiResponse(responseCode = "200", description = "Tickets returned.")
    public ResponseEntity<List<TicketResponse>> mine() {
        return ResponseEntity.ok(
                ticketRepository.findAllByRaisedById(CurrentUser.require().getUserId())
                        .stream().map(t -> toResponse(t, false)).toList());
    }

    @GetMapping("/queue")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).TICKET_READ)")
    @Operation(
            summary = "The helpdesk queue",
            description = """
                    Open work, urgent first and oldest first within each priority.

                    Clinical concerns appear here as `ESCALATED` for visibility. They cannot
                    be replied to or resolved from the helpdesk; that is the clinical
                    coordination team's.

                    **Requires** `ticket.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Queue returned.")
    public ResponseEntity<List<TicketResponse>> queue(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ticketRepository.findQueue(
                        List.of(TicketStatus.OPEN, TicketStatus.IN_PROGRESS,
                                TicketStatus.ESCALATED, TicketStatus.AWAITING_REQUESTER),
                        PageRequest.of(page, Math.min(size, 200)))
                .map(t -> toResponse(t, true)).getContent());
    }

    @PostMapping("/tickets/{ticketPublicId}/reply")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).TICKET_RESPOND)")
    @Operation(
            summary = "Reply to a ticket",
            description = """
                    Adds a message and records the first-response time when this is the
                    first reply.

                    The ten-minute target is configuration; this records what actually
                    happened, so the figure reported to FNPH is measured rather than
                    asserted.

                    Set `internal` for a staff-only note. It is never returned to the
                    requester.

                    **A clinical concern cannot receive a visible reply from the helpdesk.**
                    An internal note is allowed so context can be passed on.

                    **Requires** `ticket.respond`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Reply added."),
            @ApiResponse(responseCode = "400",
                    description = "A visible reply was attempted on a clinical concern.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> reply(
            @PathVariable String ticketPublicId,
            @Parameter(description = "The message.", required = true) @RequestParam String body,
            @Parameter(description = "Staff-only note.", example = "false")
            @RequestParam(defaultValue = "false") boolean internal) {
        helpdeskService.reply(ticketPublicId, body, internal);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tickets/{ticketPublicId}/escalate")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).TICKET_ESCALATE)")
    @Operation(
            summary = "Escalate a ticket to another team",
            description = """
                    Moves the ticket and notifies that team's desk.

                    Escalation targets are configuration rather than hard-coded, because who
                    handles a payment query is an operational decision FNPH may change
                    without a release. The clinical target is governance-owned, because who
                    answers a patient's question about their treatment is a clinical
                    decision.

                    **Requires** `ticket.escalate`.
                    """)
    @ApiResponse(responseCode = "204", description = "Escalated.")
    public ResponseEntity<Void> escalate(
            @PathVariable String ticketPublicId,
            @Parameter(description = "Target role.", example = "ICT_SUPPORT", required = true)
            @RequestParam String toRole,
            @Parameter(description = "Why.", required = true) @RequestParam String reason) {
        helpdeskService.escalate(ticketPublicId, toRole, reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tickets/{ticketPublicId}/resolve")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).TICKET_CLOSE)")
    @Operation(
            summary = "Resolve a ticket",
            description = """
                    Closes the ticket and tells the requester.

                    A summary is required. A ticket closed with no record of what was done
                    tells the next person who sees the same problem nothing, and the
                    recurring-issue reporting FNPH asked for is built from these.

                    **A clinical concern is resolved by the team it was escalated to**, not
                    by the helpdesk.

                    **Requires** `ticket.close`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Resolved."),
            @ApiResponse(responseCode = "400",
                    description = "No summary, or the helpdesk attempted to close a clinical "
                            + "concern.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> resolve(
            @PathVariable String ticketPublicId,
            @Parameter(description = "What was done.", required = true) @RequestParam String summary) {
        helpdeskService.resolve(ticketPublicId, summary);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/breaches")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).TICKET_READ)")
    @Operation(
            summary = "Tickets past the first-response target",
            description = """
                    Open tickets nobody has replied to within the configured target.

                    Measured from what happened, not asserted. This is the number that goes
                    into the service report.

                    **Requires** `ticket.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Breaching tickets returned.")
    public ResponseEntity<List<TicketResponse>> breaches() {
        return ResponseEntity.ok(helpdeskService.breachingFirstResponse()
                .stream().map(t -> toResponse(t, true)).toList());
    }

    private TicketResponse toResponse(SupportTicket ticket, boolean staffView) {
        var messages = helpdeskService.threadFor(ticket.getPublicId(), staffView).stream()
                .map(m -> new TicketResponse.Message(
                        m.getAuthorLabel(), m.getBody(),
                        Boolean.TRUE.equals(m.getIsInternal()), m.getSentAt()))
                .toList();

        return new TicketResponse(
                ticket.getPublicId(), ticket.getTicketNumber(),
                ticket.getCategory().name(), ticket.getPriority().name(),
                ticket.getStatus().name(), ticket.getSubject(),
                ticket.getEscalatedToRole(), ticket.getFirstResponseMinutes(),
                ticket.getResolutionSummary(), ticket.getCreatedAt(), messages);
    }
}
