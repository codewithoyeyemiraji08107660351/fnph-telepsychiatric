package com.fnph.telepsychiatric.scheduling.api;

import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.scheduling.*;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.user.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Rooms and doctor availability.
 *
 * These block everything downstream and had no endpoints. Approval checks that
 * the doctor is marked available across the whole slot, and nothing could set
 * that, so **every approval failed**. Publishing a schedule needs active rooms
 * of the right type, and nothing could list or add them.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Tag(name = "Administration — Rooms and Availability")
public class RoomAvailabilityController {

    private final RoomRepository roomRepository;
    private final DoctorAvailabilityRepository availabilityRepository;
    private final UserRepository userRepository;
    private final SlotRepository slotRepository;

    @GetMapping("/rooms")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).ROOM_READ)")
    @Operation(
            summary = "Consultation rooms",
            description = """
                    Active rooms, by code.

                    **Capacity comes from this list.** A published day generates one slot per
                    period per active room of the right type, so deactivating a room reduces
                    tomorrow's capacity without anyone editing a schedule.

                    `PATIENT_SERVICE` rooms serve the FNPH pathway, `CENTRE_CONSULTATION`
                    the hub-to-hub one, and `CONTINGENCY` is for a room failing mid-session.

                    **Requires** `room.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Rooms returned.")
    public ResponseEntity<List<Map<String, Object>>> rooms(
            @Parameter(description = "Filter by type.", example = "PATIENT_SERVICE")
            @RequestParam(required = false) RoomType type) {

        var rooms = type == null
                ? roomRepository.findAllByIsActiveTrueOrderByCodeAsc()
                : roomRepository.findAllByRoomTypeAndIsActiveTrueOrderByCodeAsc(type);

        return ResponseEntity.ok(rooms.stream().map(r -> {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("publicId", r.getPublicId());
            row.put("code", r.getCode());
            row.put("name", r.getName());
            row.put("roomType", r.getRoomType().name());
            row.put("capacityNotes", r.getCapacityNotes());
            return row;
        }).toList());
    }

    @PostMapping("/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).ROOM_MANAGE)")
    @Operation(
            summary = "Add a consultation room",
            description = """
                    Adding a room increases the capacity of every day published **after**
                    this point. Days already published keep the grid they were generated
                    with, because changing a published day would create slots nobody has
                    seen and remove ones patients may be looking at.

                    **Requires** `room.manage`.
                    """)
    @ApiResponse(responseCode = "201", description = "Room added.")
    @Transactional
    public ResponseEntity<Map<String, Object>> addRoom(
            @RequestParam String code,
            @RequestParam String name,
            @RequestParam RoomType roomType,
            @RequestParam(required = false) String capacityNotes) {

        roomRepository.findByCode(code).ifPresent(existing -> {
            throw new IllegalArgumentException("A room with code " + code + " already exists");
        });

        Room room = new Room();
        room.setCode(code);
        room.setName(name);
        room.setRoomType(roomType);
        room.setCapacityNotes(capacityNotes);
        room.setIsActive(true);
        Room saved = roomRepository.save(room);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("publicId", saved.getPublicId(), "code", saved.getCode()));
    }

    @PostMapping("/rooms/{roomPublicId}/deactivate")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).ROOM_MANAGE)")
    @Operation(
            summary = "Take a room out of service",
            description = """
                    Stops it being used for days published from now on.

                    **Slots already generated against it are not removed.** A booking in that
                    room belongs to a patient who has committed, and cancelling it as a side
                    effect of a room being marked out of service would be a cancellation
                    nobody decided. Block those slots individually, or withdraw the day.

                    **Requires** `room.manage`.
                    """)
    @ApiResponse(responseCode = "204", description = "Deactivated.")

    @Transactional
    public ResponseEntity<Map<String, Object>> deactivateRoom(@PathVariable String roomPublicId) {
        Room room = roomRepository.findByPublicId(roomPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such room"));
        room.setIsActive(false);
        roomRepository.save(room);

        // Deactivating only stopped new days generating slots in this room. Slots
        // already published stayed AVAILABLE, so patients kept booking a room that
        // was out of service. Close the open ones, and report the ones already
        // taken so someone moves those appointments.
        LocalDateTime now = LocalDateTime.now();
        var open = slotRepository.findAllByRoomIdAndStateAndStartAtAfter(
                room.getId(), SlotState.AVAILABLE, now);
        for (var slot : open) {
            slot.setState(SlotState.BLOCKED);
            slot.setBlockedReason("Room " + room.getCode() + " taken out of service");
        }
        slotRepository.saveAll(open);
        long taken = slotRepository.countByRoomIdAndStateInAndStartAtAfter(room.getId(),
                List.of(SlotState.HELD,
                        SlotState.BOOKED), now);

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("roomCode", room.getCode());
        body.put("openSlotsClosed", open.size());
        body.put("bookedSlotsToMove", taken);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/availability")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).DOCTOR_AVAILABILITY_MANAGE)")
    @Operation(
            summary = "Mark a doctor available or unavailable",
            description = """
                    **Approval will not succeed without this.** Approving an appointment
                    checks that the doctor is marked available across the whole slot, so a
                    doctor with no availability recorded cannot be assigned to anything.

                    Checked at assignment rather than at publication, deliberately. Slots
                    are published before anyone knows who will cover them, and a doctor going
                    on leave after a day is published must not invalidate bookings that
                    already exist. It must stop them being assigned to that doctor.

                    Set `available` false with a reason for leave. Existing approved
                    appointments are unaffected; the coordinator has to reassign them, which
                    is a decision with a phone call attached.

                    **Requires** `doctor_availability.manage`, held by the Hub Coordinator.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Recorded."),
            @ApiResponse(responseCode = "400", description = "The window ends before it starts.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @Transactional
    public ResponseEntity<Map<String, Object>> setAvailability(
            @Parameter(required = true) @RequestParam String doctorPublicId,
            @Parameter(example = "2026-11-02", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @Parameter(example = "2026-11-02T09:00:00", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startAt,
            @Parameter(example = "2026-11-02T16:00:00", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endAt,
            @RequestParam(defaultValue = "true") boolean available,
            @Parameter(description = "Required when marking unavailable.")
            @RequestParam(required = false) String reason) {

        if (!endAt.isAfter(startAt)) {
            throw new IllegalArgumentException("The window must end after it starts");
        }
        if (!available && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException(
                    "Say why the doctor is unavailable. A coordinator reassigning an "
                            + "appointment needs to know whether this is leave or a clinic day.");
        }

        var doctor = userRepository.findByPublicId(doctorPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such doctor"));

        DoctorAvailability entry = new DoctorAvailability();
        entry.setDoctor(doctor);
        entry.setServiceDate(serviceDate);
        entry.setStartAt(startAt);
        entry.setEndAt(endAt);
        entry.setIsAvailable(available);
        entry.setReason(reason);
        entry.setSetBy(CurrentUser.usernameOrSystem());
        DoctorAvailability saved = availabilityRepository.save(entry);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "publicId", saved.getPublicId(),
                "doctor", doctor.getUsername(),
                "available", available));
    }

    @GetMapping("/availability")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).DOCTOR_AVAILABILITY_READ)")
    @Operation(
            summary = "Who is available on a date",
            description = """
                    What the coordinator reads before assigning a doctor at approval.

                    An empty list means nobody has recorded availability for that day, and
                    every approval on it will be refused. That is the most common cause of
                    an approval failing.

                    **Requires** `doctor_availability.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Availability returned.")
    // Rows name each doctor, a lazy association.
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> availability(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate) {

        return ResponseEntity.ok(availabilityRepository
                .findAllByServiceDateOrderByStartAtAsc(serviceDate).stream().map(a -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("publicId", a.getPublicId());
                    row.put("doctorPublicId", a.getDoctor().getPublicId());
                    row.put("doctor", a.getDoctor().getFullName());
                    row.put("startAt", a.getStartAt());
                    row.put("endAt", a.getEndAt());
                    row.put("available", a.getIsAvailable());
                    row.put("reason", a.getReason());
                    return row;
                }).toList());
    }
}