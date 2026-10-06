package com.fnph.telepsychiatric.scheduling.api;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.appointment.AppointmentRepository;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/** The team on one consultation now, and every change that led there. */
@RestController
@RequestMapping("/api/v1/hub/appointments/{appointmentPublicId}")
@RequiredArgsConstructor
@Tag(name = "Hub Oversight")
@Transactional(readOnly = true)
public class TeamHistoryController {

    private final AppointmentRepository appointmentRepository;
    private final AppointmentTeamEventRepository eventRepository;

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

        return ResponseEntity.ok(new TeamResponse(a.getReference(), current, history));
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

    public record TeamResponse(String appointmentReference, List<Member> current, List<TeamEventRow> history) {
    }
}
