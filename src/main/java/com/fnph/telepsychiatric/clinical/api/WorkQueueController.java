package com.fnph.telepsychiatric.clinical.api;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.clinical.WorkQueueService;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Nursing and HIM preparation queues.
 *
 * Reading the queue lives in ClinicalReadController with the other read
 * surfaces. Acting on an item lives here, because picking up, completing and
 * raising an exception are state changes on the appointment and belong with a
 * mutating guard rather than beside a list endpoint.
 */
@RestController
@RequestMapping("/api/v1/queues")
@RequiredArgsConstructor
@Tag(name = "Work queues")
public class WorkQueueController {

    private final WorkQueueService workQueueService;

    // -----------------------------------------------------------------
    // Nursing
    // -----------------------------------------------------------------

    @PostMapping("/nursing/{appointmentPublicId}/start")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).QUEUE_NURSING)")
    @Operation(
            summary = "Pick up a nursing item",
            description = """
                    Moves the item from Untreated to In progress and records when.

                    Worth doing before the work rather than after: two nurses on the same
                    shift can otherwise both start on the same appointment, and the second
                    finds the vitals already entered.

                    **Requires** `queue.nursing`, and the item must be assigned to you.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Picked up."),
            @ApiResponse(responseCode = "400", description = "Not assigned to you.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Map<String, Object>> startNursing(
            @PathVariable String appointmentPublicId) {
        return ResponseEntity.ok(row(workQueueService.start(
                        appointmentPublicId, WorkQueueService.Queue.NURSING),
                WorkQueueService.Queue.NURSING));
    }

    @PostMapping("/nursing/{appointmentPublicId}/complete")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).QUEUE_NURSING)")
    @Operation(
            summary = "Mark nursing preparation complete",
            description = """
                    Records that the vitals are in the offline EHR and the room is assigned.

                    **Refused with no vitals recorded, and refused with no room assigned.**
                    Module 1 treats those as one step, and a clinician opening a session
                    relies on preparation complete meaning both are done. If the patient
                    did not submit vitals, raise an exception instead.

                    **Requires** `queue.nursing`, and the item must be assigned to you.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Preparation complete."),
            @ApiResponse(responseCode = "400",
                    description = "No vitals recorded, no room assigned, or not assigned to you.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Map<String, Object>> completeNursing(
            @PathVariable String appointmentPublicId,
            @Parameter(description = "Anything the clinician should know.",
                    example = "Vitals entered from the patient's photo upload.")
            @RequestParam(required = false) String notes) {
        return ResponseEntity.ok(row(workQueueService.complete(
                        appointmentPublicId, WorkQueueService.Queue.NURSING, notes),
                WorkQueueService.Queue.NURSING));
    }

    @PostMapping("/nursing/{appointmentPublicId}/exception")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).QUEUE_NURSING)")
    @Operation(
            summary = "Raise a nursing exception",
            description = """
                    Says the work cannot be completed, and why.

                    **The reason is mandatory.** An exception with no explanation is
                    indistinguishable from an item nobody looked at, and it is the Hub
                    Coordinator who has to act on it.

                    This is a handover, not a dismissal. The alternative is marking an item
                    treated untruthfully, which sends a clinician into a session believing
                    preparation is done.

                    **Requires** `queue.nursing`, and the item must be assigned to you.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Exception recorded."),
            @ApiResponse(responseCode = "400", description = "No reason given.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Map<String, Object>> nursingException(
            @PathVariable String appointmentPublicId,
            @Parameter(description = "What is blocking it.", required = true,
                    example = "Patient submitted no vitals and is not reachable on the number on file.")
            @RequestParam String reason) {
        return ResponseEntity.ok(row(workQueueService.raiseException(
                        appointmentPublicId, WorkQueueService.Queue.NURSING, reason),
                WorkQueueService.Queue.NURSING));
    }

    // -----------------------------------------------------------------
    // HIM
    // -----------------------------------------------------------------

    @PostMapping("/him/{appointmentPublicId}/start")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).QUEUE_HIM)")
    @Operation(
            summary = "Pick up a HIM item",
            description = """
                    Moves the item from Untreated to In progress and records when.

                    **Requires** `queue.him`, and the item must be assigned to you.
                    """)
    @ApiResponse(responseCode = "200", description = "Picked up.")
    public ResponseEntity<Map<String, Object>> startHim(
            @PathVariable String appointmentPublicId) {
        return ResponseEntity.ok(row(workQueueService.start(
                        appointmentPublicId, WorkQueueService.Queue.HIM),
                WorkQueueService.Queue.HIM));
    }

    @PostMapping("/him/{appointmentPublicId}/complete")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).QUEUE_HIM)")
    @Operation(
            summary = "Mark the record retrieved",
            description = """
                    Records that the offline record is prepared for the session.

                    HIM has no approval or rejection authority. This marks a task treated
                    and nothing else: it does not confirm the appointment, and it does not
                    change the patient record.

                    If the record cannot be found, raise an exception rather than marking
                    it treated.

                    **Requires** `queue.him`, and the item must be assigned to you.
                    """)
    @ApiResponse(responseCode = "200", description = "Marked treated.")
    public ResponseEntity<Map<String, Object>> completeHim(
            @PathVariable String appointmentPublicId,
            @Parameter(description = "Where the record is, if it helps.",
                    example = "Folder pulled and left in consulting room 2.")
            @RequestParam(required = false) String notes) {
        return ResponseEntity.ok(row(workQueueService.complete(
                        appointmentPublicId, WorkQueueService.Queue.HIM, notes),
                WorkQueueService.Queue.HIM));
    }

    @PostMapping("/him/{appointmentPublicId}/exception")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).QUEUE_HIM)")
    @Operation(
            summary = "Raise a HIM exception",
            description = """
                    Says the record cannot be retrieved, and why.

                    A missing paper record is the common case and it has to be visible
                    before the session rather than discovered during it.

                    **The reason is mandatory.**

                    **Requires** `queue.him`, and the item must be assigned to you.
                    """)
    @ApiResponse(responseCode = "200", description = "Exception recorded.")
    public ResponseEntity<Map<String, Object>> himException(
            @PathVariable String appointmentPublicId,
            @Parameter(description = "What is blocking it.", required = true,
                    example = "Record not in archive; last signed out to outpatients in June.")
            @RequestParam String reason) {
        return ResponseEntity.ok(row(workQueueService.raiseException(
                        appointmentPublicId, WorkQueueService.Queue.HIM, reason),
                WorkQueueService.Queue.HIM));
    }

    // -----------------------------------------------------------------

    private Map<String, Object> row(Appointment a, WorkQueueService.Queue queue) {
        boolean nursing = queue == WorkQueueService.Queue.NURSING;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appointmentPublicId", a.getPublicId());
        body.put("reference", a.getReference());
        body.put("queue", queue.name());
        body.put("state", workQueueService.stateOf(a, queue).name());
        body.put("startedAt", nursing ? a.getNursingStartedAt() : a.getHimStartedAt());
        body.put("completedAt", nursing ? a.getNursingCompletedAt() : a.getHimCompletedAt());
        body.put("exceptionAt", nursing ? a.getNursingExceptionAt() : a.getHimExceptionAt());
        body.put("exceptionReason",
                nursing ? a.getNursingExceptionReason() : a.getHimExceptionReason());
        return body;
    }
}