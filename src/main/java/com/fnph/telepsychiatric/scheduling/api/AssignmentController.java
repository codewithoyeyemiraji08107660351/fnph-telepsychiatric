package com.fnph.telepsychiatric.scheduling.api;

import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.scheduling.BookingService;
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
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Assigning a team one part at a time.
 *
 * The permission matrix distinguishes assigning a doctor from assigning a room,
 * and Nursing holds the room one without the doctor one. That is a real
 * workflow: a nurse moving a session because a room has a fault should not need
 * the permission to decide who consults.
 *
 * The single composite approve call still exists for the ordinary case. These
 * are for staged assignment and for changing a room after the fact, and the
 * completeness check moves to {@code /confirm} rather than disappearing.
 */
@RestController
@RequestMapping("/api/v1/hub/approvals/{appointmentPublicId}")
@RequiredArgsConstructor
@Tag(name = "Hub Coordinator — Assignment")
public class AssignmentController {

    private final BookingService bookingService;
    private final UserRepository userRepository;
    private final RoomRepository roomRepository;

    @PostMapping("/assign/doctor")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).APPOINTMENT_ASSIGN_DOCTOR)")
    @Operation(
            summary = "Assign the consulting clinician",
            description = """
                    Availability is checked here, not only at confirmation, so you find out
                    immediately rather than after filling in the rest of the team.

                    A doctor with no availability recorded for that window cannot be
                    assigned. That is the most common reason an assignment is refused, and
                    it is fixed through `/admin/availability`.

                    **Requires** `appointment.assign_doctor`, held by the Hub Coordinator.
                    Nursing does not hold it.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Doctor assigned."),
            @ApiResponse(responseCode = "400",
                    description = "Not awaiting approval, or the doctor is unavailable for "
                            + "the whole slot.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Map<String, Object>> assignDoctor(
            @PathVariable String appointmentPublicId,
            @Parameter(required = true) @RequestParam String doctorPublicId) {

        var appointment = bookingService.assignDoctor(
                appointmentPublicId, requireUser(doctorPublicId));

        return ResponseEntity.ok(Map.of(
                "reference", appointment.getReference(),
                "doctor", appointment.getDoctor().getUsername(),
                "readyToConfirm", appointment.getAssignedRoom() != null));
    }

    @PostMapping("/assign/room")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).APPOINTMENT_ASSIGN_ROOM)")
    @Operation(
            summary = "Assign or change the room",
            description = """
                    **Works after confirmation as well**, which is the point of it being a
                    separate permission.

                    A room developing a fault an hour before a session is exactly when this
                    is needed, and refusing it would leave a coordinator cancelling a
                    confirmed appointment instead of moving it next door.

                    Changing the room on a confirmed appointment **re-notifies everyone**,
                    including the patient. The room is the thing they were told to go to, and
                    the doctor's name is deliberately not disclosed, so it is the only
                    location information they have.

                    A room reserved for centre consultations is refused on this pathway.

                    **Requires** `appointment.assign_room`, held by the Hub Coordinator and
                    **Nursing**.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Room set. Everyone re-notified "
                    + "if the appointment was already confirmed."),
            @ApiResponse(responseCode = "400",
                    description = "Inactive room, a centre-only room, or the appointment is "
                            + "not pending or confirmed.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Map<String, Object>> assignRoom(
            @PathVariable String appointmentPublicId,
            @Parameter(required = true) @RequestParam String roomPublicId,
            @Parameter(description = "Why, when changing a confirmed appointment. Sent to "
                    + "everyone affected.")
            @RequestParam(required = false) String reason) {

        var room = roomRepository.findByPublicId(roomPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such room"));

        var appointment = bookingService.assignRoom(appointmentPublicId, room, reason);

        return ResponseEntity.ok(Map.of(
                "reference", appointment.getReference(),
                "room", appointment.getRoom(),
                "readyToConfirm", appointment.getDoctor() != null));
    }

    @PostMapping("/assign/team")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).APPOINTMENT_ASSIGN_TEAM)")
    @Operation(
            summary = "Assign the multidisciplinary team",
            description = """
                    Nurse, pharmacist, laboratory technician and HIM officer. All optional.

                    An unassigned pharmacist means a prescription from this consultation has
                    nobody to review it, and it will sit unassigned in the release bundle.
                    Assign one if the clinician is likely to prescribe.

                    Each person assigned is notified at confirmation, not here, so a
                    coordinator can change their mind while building the team without
                    sending three notices to the same nurse.

                    **Requires** `appointment.assign_team`.
                    """)
    @ApiResponse(responseCode = "200", description = "Team assigned.")
    public ResponseEntity<Map<String, Object>> assignTeam(
            @PathVariable String appointmentPublicId,
            @RequestParam(required = false) String nursePublicId,
            @RequestParam(required = false) String pharmacistPublicId,
            @RequestParam(required = false) String laboratoryPublicId,
            @RequestParam(required = false) String himPublicId) {

        var appointment = bookingService.assignTeam(appointmentPublicId,
                optionalUser(nursePublicId), optionalUser(pharmacistPublicId),
                optionalUser(laboratoryPublicId), optionalUser(himPublicId));

        return ResponseEntity.ok(Map.of(
                "reference", appointment.getReference(),
                "readyToConfirm",
                appointment.getDoctor() != null && appointment.getAssignedRoom() != null));
    }



    @PostMapping("/confirm")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).APPOINTMENT_APPROVE)")
    @Operation(
            summary = "Confirm an appointment assembled through staged assignment",
            description = """
                    Confirms the appointment, debits the patient's wallet, opens the join
                    window and notifies everyone.

                    **This is where the completeness check lives.** Refused without a doctor,
                    because there would be nobody to consult, and refused without a room,
                    because the patient would be told to go nowhere. Either would produce an
                    appointment the patient believes is booked and that cannot run.

                    Use the composite `/approve` when you have all the details to hand. Use
                    the staged endpoints and this when assignment happens in more than one
                    sitting, or when Nursing sets the room.

                    **Requires** `appointment.approve`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Confirmed and wallet debited."),
            @ApiResponse(responseCode = "400",
                    description = "No doctor, no room, or not awaiting approval. The message "
                            + "says which.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Map<String, Object>> confirm(
            @PathVariable String appointmentPublicId,
            @RequestParam(required = false) String notes) {

        var appointment = bookingService.confirm(appointmentPublicId, notes);

        return ResponseEntity.ok(Map.of(
                "reference", appointment.getReference(),
                "status", appointment.getStatus().name(),
                "room", appointment.getRoom(),
                "joinWindowOpensAt", String.valueOf(appointment.getJoinWindowOpensAt())));
    }

    private Users requireUser(String publicId) {
        return userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new EntityNotFoundException("No such user"));
    }

    private Users optionalUser(String publicId) {
        return publicId == null || publicId.isBlank()
                ? null : userRepository.findByPublicId(publicId).orElse(null);
    }
}
