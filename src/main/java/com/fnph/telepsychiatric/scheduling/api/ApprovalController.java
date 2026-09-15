package com.fnph.telepsychiatric.scheduling.api;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.appointment.AppointmentRepository;
import com.fnph.telepsychiatric.appointment.Status;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.scheduling.BookingService;
import com.fnph.telepsychiatric.scheduling.Room;
import com.fnph.telepsychiatric.scheduling.RoomRepository;
import com.fnph.telepsychiatric.user.UserRepository;
import com.fnph.telepsychiatric.user.Users;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/hub/approvals")
@RequiredArgsConstructor
@Tag(name = "Hub Coordinator — Approvals")
public class ApprovalController {

    private final BookingService bookingService;
    private final AppointmentRepository appointmentRepository;
    private final UserRepository userRepository;
    private final RoomRepository roomRepository;

    @GetMapping
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).APPOINTMENT_READ)")
    @Operation(
            summary = "Paid requests waiting on a decision",
            description = """
                    Appointments in `AWAITING_APPROVAL`, earliest appointment first.

                    Every one of these has been **paid for**. The patient's wallet was
                    credited when the payment cleared and is spent only when you approve, so
                    a rejection costs them nothing and the amount carries to their next
                    booking automatically.

                    The slot is already `BOOKED`, not merely held. The patient must not lose
                    their time while this queue is worked through.

                    **Requires** `appointment.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Queue returned.",
            content = @Content(schema = @Schema(implementation = ScheduleDtos.QueueResponse.class)))
    public ResponseEntity<ScheduleDtos.QueueResponse> queue(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        var results = appointmentRepository.findAllByStatusOrderByAppointmentDateAsc(
                Status.AWAITING_APPROVAL, PageRequest.of(page, Math.min(size, 200)));

        return ResponseEntity.ok(new ScheduleDtos.QueueResponse(
                appointmentRepository.countByStatus(Status.AWAITING_APPROVAL),
                results.map(this::toResponse).getContent()));
    }

    /** The four multidisciplinary team roles, and nothing else. */
    private static final List<String> ASSIGNABLE_ROLES =
            List.of("NURSING", "PHARMACIST", "LABORATORY_TECHNICIAN", "HIM");

    @GetMapping("/assignable-staff")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).APPOINTMENT_ASSIGN_TEAM)")
    @Operation(
            summary = "Staff assignable to a consultation team",
            description = """
                    Active hospital staff holding one of the four team roles, with the role
                    each holds, so the Hub Coordinator can name a team when approving.

                    **Gated on `appointment.assign_team`, not `user.read`.** The coordinator
                    has to name a nurse and a pharmacist to approve an appointment and does
                    not hold `user.read`, which would hand them the whole account
                    administration surface to fill four dropdowns. This returns a name and
                    role codes: no email, no account status, no sign-in history.

                    Centre staff are excluded by construction. A centre pharmacist holds
                    `CENTRE_PHARMACY`, not `PHARMACIST`, so querying the four FNPH role
                    codes cannot reach them. An FNPH consultation team is drawn from the
                    hospital.

                    **Requires** `appointment.assign_team`.
                    """)
    @ApiResponse(responseCode = "200", description = "Assignable staff, ordered by name.")
    public ResponseEntity<List<Map<String, Object>>> assignableStaff(
            @Parameter(description = "Limit to one of the four team role codes. Omit for all.",
                    example = "PHARMACIST")
            @RequestParam(required = false) String role) {

        List<String> wanted = role == null ? ASSIGNABLE_ROLES
                : ASSIGNABLE_ROLES.stream().filter(role::equals).toList();

        // Keyed by public id, because one person can legitimately hold two of
        // these roles and must appear once with both rather than twice.
        Map<String, Map<String, Object>> byPublicId = new java.util.LinkedHashMap<>();

        for (String roleCode : wanted) {
            for (Users user : userRepository.findActiveByRoleCode(roleCode)) {
                // Belt and braces. The role codes above are FNPH-scoped so a
                // centre account should not match, and a mis-assigned one must
                // not end up on a hospital consultation team either way.
                if (user.getCentre() != null) {
                    continue;
                }
                Map<String, Object> row = byPublicId.computeIfAbsent(
                        user.getPublicId(), key -> {
                            Map<String, Object> fresh = new java.util.LinkedHashMap<>();
                            fresh.put("publicId", user.getPublicId());
                            fresh.put("fullName", user.getFullName());
                            fresh.put("roles", new java.util.ArrayList<String>());
                            return fresh;
                        });
                @SuppressWarnings("unchecked")
                List<String> roles = (List<String>) row.get("roles");
                if (!roles.contains(roleCode)) {
                    roles.add(roleCode);
                }
            }
        }

        return ResponseEntity.ok(byPublicId.values().stream()
                .sorted(Comparator.comparing(r -> String.valueOf(r.get("fullName"))))
                .toList());
    }

    @PostMapping("/{appointmentPublicId}/approve")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).APPOINTMENT_APPROVE)")
    @Operation(
            summary = "Approve and assign the team",
            description = """
                    Confirms the appointment, assigns the multidisciplinary team and the
                    room, spends the patient's wallet balance and notifies everyone who now
                    has work.

                    **Checked before anything is committed.** The doctor must be marked
                    available for the whole slot, and the room must be active and not one
                    reserved for centre consultations. Assigning a doctor who is on leave
                    produces an appointment that looks confirmed to the patient and cannot
                    run.

                    Each assignee receives a notice naming the room, date and time.
                    Assignments go to the person, not the role, because only that named
                    clinician can act on it.

                    **Approval is what spends the money.** Payment credited the wallet; this
                    debits it. That is why a rejection leaves the balance intact.

                    **Requires** `appointment.approve`, held by the Hub Coordinator.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Approved, assigned and notified.",
                    content = @Content(schema = @Schema(implementation = ScheduleDtos.AppointmentResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Not awaiting approval, doctor unavailable for the whole "
                            + "slot, or the room is inactive or reserved for centres.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ScheduleDtos.AppointmentResponse> approve(
            @PathVariable String appointmentPublicId,
            @Valid @RequestBody ScheduleDtos.ApproveAppointmentRequest request) {

        Room room = roomRepository.findByPublicId(request.roomPublicId())
                .orElseThrow(() -> new EntityNotFoundException("No such room"));

        var assignment = new BookingService.AssignmentRequest(
                requireUser(request.doctorPublicId(), "doctor"),
                optionalUser(request.nursePublicId()),
                optionalUser(request.pharmacistPublicId()),
                optionalUser(request.laboratoryTechnicianPublicId()),
                optionalUser(request.himOfficerPublicId()),
                room, request.notes());

        return ResponseEntity.ok(toResponse(
                bookingService.approve(appointmentPublicId, assignment)));
    }

    @PostMapping("/{appointmentPublicId}/reject")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).APPOINTMENT_REJECT)")
    @Operation(
            summary = "Turn down a paid request",
            description = """
                    Releases the slot and tells the patient.

                    **Nothing is refunded and nothing is taken.** The amount they paid stays
                    on their account as a balance and applies automatically to their next
                    booking. Payment is non-refundable by decision, and this is what stops
                    that leaving a patient out of pocket for an administrative choice they
                    had no part in.

                    The reason is sent to the patient. Write it for them, not for the file.

                    **Requires** `appointment.reject`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rejected. Slot released, balance kept."),
            @ApiResponse(responseCode = "400", description = "Not awaiting approval.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ScheduleDtos.AppointmentResponse> reject(
            @PathVariable String appointmentPublicId,
            @Parameter(description = "Why. Shown to the patient.", required = true)
            @RequestParam String reason) {
        return ResponseEntity.ok(toResponse(bookingService.reject(appointmentPublicId, reason)));
    }

    @GetMapping("/{appointmentPublicId}/history")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).APPOINTMENT_READ)")
    @Operation(
            summary = "Reconstruct what happened to an appointment",
            description = """
                    Every status change with who made it, when and why.

                    Reconstructing who approved what is an acceptance requirement, and the
                    current status cannot answer it: after a rejection nothing on the
                    appointment says a request was ever made.

                    **Requires** `appointment.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "History returned, oldest first.")
    public ResponseEntity<List<ScheduleDtos.HistoryResponse>> history(
            @PathVariable String appointmentPublicId) {

        Appointment appointment = appointmentRepository.findByPublicId(appointmentPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such appointment"));

        return ResponseEntity.ok(bookingService.historyFor(appointment.getId()).stream()
                .map(h -> new ScheduleDtos.HistoryResponse(
                        h.getFromStatus(), h.getToStatus(), h.getChangedBy(),
                        h.getChangedAt(), h.getReason()))
                .toList());
    }

    private Users requireUser(String publicId, String label) {
        return userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new EntityNotFoundException("No such " + label));
    }

    private Users optionalUser(String publicId) {
        return publicId == null || publicId.isBlank()
                ? null : userRepository.findByPublicId(publicId).orElse(null);
    }

    private ScheduleDtos.AppointmentResponse toResponse(Appointment a) {
        return new ScheduleDtos.AppointmentResponse(
                a.getPublicId(), a.getReference(), a.getStatus().name(),
                a.getAppointmentDate(), a.getScheduledEndAt(), a.getHeldUntil(),
                a.getRoom(), a.getJoinWindowOpensAt(), a.getRejectedReason());
    }
}
