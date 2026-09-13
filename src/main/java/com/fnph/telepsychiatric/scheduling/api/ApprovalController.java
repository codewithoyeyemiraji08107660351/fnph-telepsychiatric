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

import java.util.List;

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
