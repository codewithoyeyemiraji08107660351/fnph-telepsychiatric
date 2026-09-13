package com.fnph.telepsychiatric.scheduling.api;

import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.scheduling.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/schedules")
@RequiredArgsConstructor
@Tag(name = "Administration — Schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).SCHEDULE_PUBLISH)")
    @Operation(
            summary = "Open a consultation day",
            description = """
                    Generates the whole day's slots in one transaction.

                    **A day is published whole or not at all.** A half-published day shows a
                    patient two available times out of sixteen and reads as a fully booked
                    clinic, which is worse than no day at all because nobody reports it.

                    **Slot length is not a parameter.** It comes from configuration per
                    audience, so changing the Centre duration cannot silently move FNPH
                    appointments.

                    **Capacity is not a parameter either.** One slot per period per active
                    consultation room. That makes capacity a real thing rather than a number
                    somebody typed, and taking a room out of service reduces tomorrow's
                    capacity without anyone editing a schedule.

                    Set `publishImmediately` false to generate the grid and check it before
                    patients can see it.

                    **Requires** `schedule.publish`, held by the Hub Coordinator.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Day opened and slots generated.",
                    content = @Content(schema = @Schema(implementation = ScheduleDtos.PublicationResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Past date, inverted window, a day already exists for that "
                            + "audience, or no active rooms of the right type.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ScheduleDtos.PublicationResponse> publish(
            @Valid @RequestBody ScheduleDtos.PublishScheduleRequest request) {

        var publication = scheduleService.publish(
                ScheduleAudience.valueOf(request.audience()), request.serviceDate(),
                request.windowStart(), request.windowEnd(), request.publishImmediately());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(publication));
    }

    @GetMapping
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).SCHEDULE_READ)")
    @Operation(
            summary = "Published days in a date range",
            description = """
                    Both audiences, oldest first, with how many slots each generated.

                    A day with fewer slots than you expect usually means rooms were
                    inactive when it was published, not that the generator failed.

                    **Requires** `schedule.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Days returned.")
    public ResponseEntity<List<ScheduleDtos.PublicationResponse>> list(
            @Parameter(description = "From, inclusive.", example = "2026-11-01")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "To, inclusive.", example = "2026-11-30")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(scheduleService.publicationsBetween(from, to)
                .stream().map(this::toResponse).toList());
    }

    @GetMapping("/{publicationPublicId}/slots")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).SCHEDULE_READ)")
    @Operation(
            summary = "Every slot on a day, with its state",
            description = """
                    The coordinator's view of a day: which periods are free, held, booked or
                    blocked, and in which room.

                    Patients see a collapsed version through the booking endpoint. This one
                    shows the rooms because the coordinator is the person who assigns them.

                    **Requires** `schedule.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Slots returned in time order.")
    public ResponseEntity<List<Map<String, Object>>> slots(@PathVariable String publicationPublicId) {
        return ResponseEntity.ok(scheduleService.slotsOf(publicationPublicId).stream()
                .map(s -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("publicId", s.getPublicId());
                    row.put("startAt", s.getStartAt());
                    row.put("endAt", s.getEndAt());
                    row.put("state", s.getState().name());
                    row.put("room", s.getRoom() == null ? null : s.getRoom().getCode());
                    row.put("blockedReason", s.getBlockedReason());
                    return row;
                }).toList());
    }

    @PostMapping("/{publicationPublicId}/publish")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).SCHEDULE_PUBLISH)")
    @Operation(
            summary = "Make a draft day visible to patients",
            description = "**Requires** `schedule.publish`.")
    @ApiResponse(responseCode = "200", description = "Published.")
    public ResponseEntity<ScheduleDtos.PublicationResponse> publishDraft(
            @PathVariable String publicationPublicId) {
        return ResponseEntity.ok(toResponse(scheduleService.publishDraft(publicationPublicId)));
    }

    @PostMapping("/{publicationPublicId}/withdraw")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).SCHEDULE_PUBLISH)")
    @Operation(
            summary = "Stop taking bookings on a day",
            description = """
                    Blocks every untouched slot and leaves existing bookings standing.

                    **Withdrawing does not cancel anything.** A held or booked slot belongs
                    to a patient who has already committed, and taking it away here would
                    cancel their appointment as a side effect of an administrative change.
                    Cancelling is a decision with a phone call attached.

                    The audit entry records how many bookings were left standing, which is
                    the number the coordinator then has to work through by hand.

                    **Requires** `schedule.publish`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Withdrawn. Existing bookings stand."),
            @ApiResponse(responseCode = "400", description = "No reason given.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ScheduleDtos.PublicationResponse> withdraw(
            @PathVariable String publicationPublicId,
            @Parameter(description = "Why. Shown on every blocked slot.", required = true)
            @RequestParam String reason) {
        return ResponseEntity.ok(toResponse(scheduleService.withdraw(publicationPublicId, reason)));
    }

    @PostMapping("/slots/{slotPublicId}/block")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).SCHEDULE_PUBLISH)")
    @Operation(
            summary = "Take one slot out of service",
            description = """
                    For a room fault or a doctor called away.

                    Refused if the slot is held or booked. Somebody is already relying on
                    it, and removing it silently would leave a patient arriving for an
                    appointment that no longer exists.

                    **Requires** `schedule.publish`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Blocked."),
            @ApiResponse(responseCode = "400", description = "The slot is already taken.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> blockSlot(@PathVariable String slotPublicId,
                                          @RequestParam String reason) {
        scheduleService.blockSlot(slotPublicId, reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/slots/{slotPublicId}/unblock")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).SCHEDULE_PUBLISH)")
    @Operation(summary = "Return a blocked slot to the pool",
            description = "**Requires** `schedule.publish`.")
    @ApiResponse(responseCode = "204", description = "Available again.")
    public ResponseEntity<Void> unblockSlot(@PathVariable String slotPublicId) {
        scheduleService.unblockSlot(slotPublicId);
        return ResponseEntity.noContent().build();
    }

    private ScheduleDtos.PublicationResponse toResponse(SchedulePublication p) {
        return new ScheduleDtos.PublicationResponse(
                p.getPublicId(), p.getAudience().name(), p.getServiceDate(),
                p.getWindowStart(), p.getWindowEnd(), p.getSlotMinutes(),
                p.getStatus().name(), p.getSlotsGenerated(),
                p.getPublishedBy(), p.getPublishedAt(), p.getWithdrawReason());
    }
}
