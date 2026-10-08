package com.fnph.telepsychiatric.staff.api;

import com.fnph.telepsychiatric.common.HospitalClock;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.security.SecurityUser;
import com.fnph.telepsychiatric.staff.StaffWorkService;
import com.fnph.telepsychiatric.user.Users;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Work history and dashboards for doctors, pharmacists, laboratory technicians
 * and HIM officers: their own, and for the Hub Coordinator, anyone's.
 *
 * Dates are hospital days (WAT), inclusive. Omitted, the window is the last
 * 30 days up to today.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Staff Work History")
public class StaffWorkController {

    private final StaffWorkService service;

    // -----------------------------------------------------------------
    // My work
    // -----------------------------------------------------------------

    @GetMapping("/me/work")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "My work dashboard",
            description = """
                    For a doctor, pharmacist, laboratory technician or HIM officer: what is
                    waiting on you now (`now`), what you did in the window (`period`), and
                    a daily series for the chart.

                    `role` picks one of your roles when you hold more than one; `roles`
                    lists them. Omitted, your first is used.

                    Your own work only. No permission beyond holding one of those roles,
                    because the numbers describe nobody but you.
                    """)
    @ApiResponse(responseCode = "200", description = "Dashboard returned.")
    @ApiResponse(responseCode = "403", description = "Not a doctor, reviewer or HIM officer.")
    public ResponseEntity<StaffWorkService.Summary> mySummary(
            @Parameter(description = "DOCTOR, PHARMACIST, LABORATORY or HIM") @RequestParam(required = false) String role,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Users me = me();
        LocalDate end = to == null ? HospitalClock.today() : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        return ResponseEntity.ok(service.summary(me, service.resolveRole(me, role), start, end));
    }

    @GetMapping("/me/work/items")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "My work history",
            description = """
                    Newest first, a page at a time.

                    * Doctor: every consultation you were assigned in the window, with the
                      session times, whether the note is signed, and what was issued.
                    * Pharmacist or laboratory technician: every review assigned or
                      submitted in the window, plus any still waiting on you.
                    * HIM officer: record retrievals (`kind=RECORDS`, the default) or the
                      enrolment checks you decided (`kind=VERIFICATIONS`).

                    `q` matches patient name, EHR number or reference.
                    """)
    @ApiResponse(responseCode = "200", description = "A page of history.")
    public ResponseEntity<StaffWorkService.Page> myItems(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Users me = me();
        LocalDate end = to == null ? HospitalClock.today() : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        return ResponseEntity.ok(service.items(me, service.resolveRole(me, role), start, end, kind, q, page, size));
    }

    // -----------------------------------------------------------------
    // Hub Coordinator
    // -----------------------------------------------------------------

    @GetMapping("/hub/staff-work")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).RELEASE_BUNDLE_READ)")
    @Operation(
            summary = "Team members and their headline numbers",
            description = """
                    Every active doctor, pharmacist, laboratory technician and HIM officer,
                    one row per role held, with what they did in the window and what is
                    waiting on them now.

                    **Requires** `release_bundle.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Staff returned.")
    public ResponseEntity<List<StaffWorkService.StaffRow>> staff(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to == null ? HospitalClock.today() : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        return ResponseEntity.ok(service.staff(start, end));
    }

    @GetMapping("/hub/staff-work/{userPublicId}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).RELEASE_BUNDLE_READ)")
    @Operation(
            summary = "One team member's dashboard",
            description = "The same view the person sees for themselves. **Requires** `release_bundle.read`.")
    @ApiResponse(responseCode = "200", description = "Dashboard returned.")
    @ApiResponse(responseCode = "404", description = "No such person, or no work history for their roles.")
    public ResponseEntity<StaffWorkService.Summary> theirSummary(
            @PathVariable String userPublicId,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Users user = service.requireUser(userPublicId);
        LocalDate end = to == null ? HospitalClock.today() : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        return ResponseEntity.ok(service.summary(user, service.resolveRole(user, role), start, end));
    }

    @GetMapping("/hub/staff-work/{userPublicId}/items")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).RELEASE_BUNDLE_READ)")
    @Operation(summary = "One team member's work history",
            description = "As `/me/work/items`, for anyone. **Requires** `release_bundle.read`.")
    @ApiResponse(responseCode = "200", description = "A page of history.")
    public ResponseEntity<StaffWorkService.Page> theirItems(
            @PathVariable String userPublicId,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Users user = service.requireUser(userPublicId);
        LocalDate end = to == null ? HospitalClock.today() : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        return ResponseEntity.ok(service.items(user, service.resolveRole(user, role), start, end, kind, q, page, size));
    }

    /** Staff only. Patients and centre accounts have no work history here. */
    private Users me() {
        SecurityUser principal = CurrentUser.require();
        if (principal.getPatientId() != null || principal.getCentreId() != null) {
            throw new AccessDeniedException("Work history covers hospital clinical staff only");
        }
        Users user = service.requireUser(principal.getUserId());
        if (service.rolesOf(user).isEmpty()) {
            throw new AccessDeniedException("Work history covers doctors, pharmacists, laboratory "
                    + "technicians and HIM officers");
        }
        return user;
    }
}
