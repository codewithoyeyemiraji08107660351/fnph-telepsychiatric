package com.fnph.telepsychiatric.scheduling.api;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.appointment.AppointmentRepository;
import com.fnph.telepsychiatric.clinical.ProfessionalReviewService;
import com.fnph.telepsychiatric.clinical.ReleaseBundle;
import com.fnph.telepsychiatric.clinical.ReleaseBundleRepository;
import com.fnph.telepsychiatric.clinical.ReviewType;
import com.fnph.telepsychiatric.hub.HubTeamService;
import com.fnph.telepsychiatric.scheduling.AppointmentTeamEvent;
import com.fnph.telepsychiatric.scheduling.AppointmentTeamEventRepository;
import com.fnph.telepsychiatric.user.Users;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The team on one consultation now, and every change that led there. */
@RestController
@RequestMapping("/api/v1/hub/appointments/{appointmentPublicId}")
@RequiredArgsConstructor
@Tag(name = "Hub Oversight")
@Transactional(readOnly = true)
public class TeamHistoryController {

    private final AppointmentRepository appointmentRepository;
    private final AppointmentTeamEventRepository eventRepository;
    private final ReleaseBundleRepository bundleRepository;
    private final ProfessionalReviewService reviewService;
    private final HubTeamService teamService;

    @GetMapping("/team")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).RELEASE_BUNDLE_READ)")
    @Operation(
            summary = "Care team and its history",
            description = """
                    `current` is the doctor, nurse, pharmacist, laboratory technician, HIM
                    officer and room as they stand, with null for a role nobody holds.

                    `history` is every change, newest first. `BACKFILL` rows record the
                    team each appointment already had when history began; changes made
                    before that were never recorded.
                    """)
    @ApiResponse(responseCode = "200", description = "Team returned.")
    public ResponseEntity<TeamResponse> team(@PathVariable String appointmentPublicId) {
        Appointment a = appointmentRepository.findByPublicId(appointmentPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such appointment"));
        return ResponseEntity.ok(teamOf(a));
    }

    @PostMapping("/team/{role}")
    @Transactional
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).APPOINTMENT_ASSIGN_TEAM)")
    @Operation(
            summary = "Assign or reassign one team member after approval",
            description = """
                    `role` is DOCTOR, NURSE, PHARMACIST, LABORATORY or HIM. The person must
                    be active hospital staff holding the matching role. `reason` is required
                    (at least 10 characters) and is kept in the team history.

                    Each role locks once its work is done: the doctor when the session
                    starts, the nurse and HIM officer when they record completion, and
                    everyone once the bundle is released. `locked` in the team response
                    says which roles are locked and why.

                    For PHARMACIST and LABORATORY, unsubmitted reviews move to the new
                    person, including reviews that were created with nobody assigned.
                    Choosing the person already in the role does exactly that and nothing
                    else.

                    Changing the doctor also needs `appointment.assign_doctor` and checks
                    the doctor's availability and other consultations.

                    **Requires** `appointment.assign_team`.
                    """)
    @ApiResponse(responseCode = "200", description = "Changed. Returns the team as it now stands.")
    @ApiResponse(responseCode = "409", description = "That role is locked, or the doctor is not free.")
    public ResponseEntity<TeamResponse> reassign(@PathVariable String appointmentPublicId,
                                                 @PathVariable String role,
                                                 @RequestBody ReassignRequest request) {
        HubTeamService.Role teamRole;
        try {
            teamRole = HubTeamService.Role.valueOf(role.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Role must be DOCTOR, NURSE, PHARMACIST, LABORATORY or HIM");
        }
        if (request == null || request.userPublicId() == null || request.userPublicId().isBlank()) {
            throw new IllegalArgumentException("Choose who to assign");
        }
        teamService.reassign(appointmentPublicId, teamRole, request.userPublicId(), request.reason());

        Appointment a = appointmentRepository.findByPublicId(appointmentPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such appointment"));
        return ResponseEntity.ok(teamOf(a));
    }

    private TeamResponse teamOf(Appointment a) {

        List<Member> current = List.of(
                member("DOCTOR", a.getDoctor()),
                member("NURSE", a.getNurse()),
                member("PHARMACIST", a.getPharmacist()),
                member("LABORATORY", a.getLaboratoryTechnician()),
                member("HIM", a.getHimOfficer()),
                new Member("ROOM", null,
                        a.getAssignedRoom() == null ? null : a.getAssignedRoom().getCode(),
                        a.getAssignedRoom() == null ? null : a.getAssignedRoom().getName()));

        List<TeamEventRow> history = eventRepository
                .findAllByAppointmentIdOrderByChangedAtDescIdDesc(a.getId())
                .stream()
                .map(TeamHistoryController::row)
                .toList();

        List<String> changeable = new ArrayList<>();
        Map<String, String> locked = new LinkedHashMap<>();
        teamService.locks(a).forEach((role, reason) -> {
            if (reason == null) {
                changeable.add(role.name());
            } else {
                locked.put(role.name(), reason);
            }
        });

        Map<String, Integer> openReviews = new LinkedHashMap<>();
        ReleaseBundle bundle = bundleRepository.findByAppointmentId(a.getId()).orElse(null);
        if (bundle != null) {
            openReviews.put("PHARMACIST", reviewService.openLive(bundle.getId(), ReviewType.PHARMACY).size());
            openReviews.put("LABORATORY", reviewService.openLive(bundle.getId(), ReviewType.LABORATORY).size());
        }

        return new TeamResponse(a.getReference(), a.getStatus().name(), current, history,
                changeable, locked, openReviews,
                a.getAppointmentDate(), a.getScheduledEndAt());
    }

    public record ReassignRequest(String userPublicId, String reason) {
    }

    private static Member member(String role, Users user) {
        return user == null
                ? new Member(role, null, null, null)
                : new Member(role, user.getPublicId(), user.getFullName(), null);
    }

    private static TeamEventRow row(AppointmentTeamEvent e) {
        return new TeamEventRow(
                e.getPublicId(),
                e.getTeamRole().name(),
                e.getFromLabel(),
                e.getToLabel(),
                e.getChangeSource().name(),
                e.getReason(),
                e.getChangedBy(),
                e.getChangedAt());
    }

    /** {@code name} is the person, or the room code; {@code detail} is the room's name. */
    public record Member(String role, String publicId, String name, String detail) {
    }

    public record TeamEventRow(
            String eventPublicId,
            String role,
            String fromLabel,
            String toLabel,
            String source,
            String reason,
            String changedBy,
            LocalDateTime changedAt) {
    }

    /**
     * {@code changeable} lists the roles the coordinator can change now;
     * {@code locked} gives the reason for each role that cannot.
     * {@code openReviews} counts unsubmitted reviews that would move with the
     * pharmacist or laboratory role. The slot is for picking a free doctor.
     */
    public record TeamResponse(String appointmentReference,
                               String appointmentStatus,
                               List<Member> current,
                               List<TeamEventRow> history,
                               List<String> changeable,
                               Map<String, String> locked,
                               Map<String, Integer> openReviews,
                               LocalDateTime appointmentDate,
                               LocalDateTime scheduledEndAt) {
    }
}
