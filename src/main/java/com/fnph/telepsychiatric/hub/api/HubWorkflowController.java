package com.fnph.telepsychiatric.hub.api;

import com.fnph.telepsychiatric.common.HospitalClock;
import com.fnph.telepsychiatric.hub.HubStatsService;
import com.fnph.telepsychiatric.hub.HubWorkflowService;
import com.fnph.telepsychiatric.hub.WorkflowStage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * The Hub Coordinator dashboard: queue and period numbers, the workflow board,
 * and one consultation's full journey. Read-only.
 */
@RestController
@RequestMapping("/api/v1/hub")
@RequiredArgsConstructor
@Tag(name = "Hub Oversight")
public class HubWorkflowController {

    private final HubWorkflowService workflowService;
    private final HubStatsService statsService;

    @GetMapping("/stats")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).RELEASE_BUNDLE_READ)")
    @Operation(
            summary = "Dashboard numbers",
            description = """
                    `now` is the state of each queue at this moment and ignores the window.
                    `period`, `daily` and `workload` cover the hospital days `from` to `to`
                    inclusive (WAT). Defaults to the last 30 days including today.

                    Averages are in hours and absent when nothing in the window had both ends.
                    `workload` counts consultations each person was assigned to (approved or
                    later) and the reviews they still have open.

                    **Requires** `release_bundle.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Numbers returned.")
    public ResponseEntity<HubStatsService.Stats> stats(
            @Parameter(example = "2026-09-07")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(example = "2026-10-06")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        LocalDate end = to == null ? HospitalClock.today() : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        return ResponseEntity.ok(statsService.stats(start, end));
    }

    @GetMapping("/workflow")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).RELEASE_BUNDLE_READ)")
    @Operation(
            summary = "Workflow board",
            description = """
                    Every FNPH consultation dated in the window (WAT, inclusive), newest
                    first, with its team, stage, review state, bundle and follow-up.

                    `stageCounts` always covers the whole window, so the stage tabs keep
                    their numbers while one is selected. `stage` and `q` (reference, EHR
                    number or patient name) narrow the rows. Defaults to 30 days back and
                    14 days ahead. At most 92 days.

                    **Requires** `release_bundle.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Board returned.")
    public ResponseEntity<HubWorkflowService.Board> workflow(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) WorkflowStage stage,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        LocalDate today = HospitalClock.today();
        LocalDate start = from == null ? today.minusDays(30) : from;
        LocalDate end = to == null ? today.plusDays(14) : to;
        return ResponseEntity.ok(workflowService.board(start, end, stage, q, page, size));
    }

    @GetMapping("/appointments/{appointmentPublicId}/timeline")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).RELEASE_BUNDLE_READ)")
    @Operation(
            summary = "One consultation, booking to follow-up",
            description = """
                    Every recorded step in time order: booking and status changes, team
                    assignments, session joins and leaves, note signing, prescriptions and
                    investigations, pharmacy and laboratory reviews, hub edits, release and
                    follow-up. Plus the current stage, team and the elapsed time between
                    milestones (minutes).

                    Built from existing records. Steps from before team history existed
                    show only what was recorded at the time.

                    **Requires** `release_bundle.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Timeline returned.")
    public ResponseEntity<HubWorkflowService.Timeline> timeline(@PathVariable String appointmentPublicId) {
        return ResponseEntity.ok(workflowService.timeline(appointmentPublicId));
    }
}
