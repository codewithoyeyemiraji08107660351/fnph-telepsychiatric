package com.fnph.telepsychiatric.scheduling.api;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.appointment.AppointmentRepository;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.patient.PatientRepository;
import com.fnph.telepsychiatric.scheduling.BookingService;
import com.fnph.telepsychiatric.scheduling.ScheduleAudience;
import com.fnph.telepsychiatric.security.CurrentUser;
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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/booking")
@RequiredArgsConstructor
@Tag(name = "Booking")
public class BookingController {

    private final BookingService bookingService;
    private final com.fnph.telepsychiatric.patient.PatientJourneyService journey;

    public record SubmitRequest(String intakePublicId, String slotPublicId) {}

    @PostMapping("/request")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).SLOT_HOLD)")
    public ScheduleDtos.AppointmentResponse submit(@RequestBody SubmitRequest request) {
        return toResponse(journey.submit(CurrentUser.require().getPatientId(), request.intakePublicId(), request.slotPublicId()));
    }

    @GetMapping("/days")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).SLOT_READ)")
    public List<LocalDate> days() {
        journey.requirePayment(CurrentUser.require().getPatientId());
        LocalDate end = LocalDate.now().plusMonths(3);
        return bookingService.bookableSlots(ScheduleAudience.FNPH_PATIENT).stream()
                .map(s -> s.getStartAt().toLocalDate()).filter(d -> !d.isAfter(end)).distinct().sorted().toList();
    }
    private final AppointmentRepository appointmentRepository;
    private final PatientRepository patientRepository;
    private final com.fnph.telepsychiatric.triage.TriageService triageService;

    @GetMapping("/times")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).SLOT_READ)")
    @Operation(
            summary = "Available times on a date",
            description = """
                    One entry per bookable period, with how many rooms are still free at it.

                    **Times are collapsed across rooms.** Four rooms running at 09:00 is one
                    entry with `remaining` of four. Asking a patient to choose between four
                    identical times is asking a question they cannot answer, and which room
                    they get is the Hub Coordinator's decision at approval.

                    Times inside the room-open lead are excluded. A slot the patient could
                    not reach in time is worse than no slot, because on this pathway they
                    pay for it first.

                    **Requires** `slot.read`. Broader than `slot.hold`: a Centre Assistant
                    Coordinator can look at times and cannot take one.
                    """)
    @ApiResponse(responseCode = "200", description = "Times returned in order.",
            content = @Content(schema = @Schema(implementation = ScheduleDtos.AvailableTimeResponse.class)))
    public ResponseEntity<List<ScheduleDtos.AvailableTimeResponse>> times(
            @Parameter(description = "The date to look at.", example = "2026-11-02", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        if (CurrentUser.patientId().isPresent()) {
            journey.requirePayment(CurrentUser.require().getPatientId());
            if (date.isBefore(LocalDate.now()) || date.isAfter(LocalDate.now().plusMonths(3))) {
                throw new IllegalArgumentException("Choose a published date within the next three months");
            }
        }
        return ResponseEntity.ok(
                bookingService.availableTimes(ScheduleAudience.FNPH_PATIENT, date).stream()
                        .map(t -> new ScheduleDtos.AvailableTimeResponse(
                                t.startAt(), t.endAt(), t.slotPublicId(), t.remaining()))
                        .toList());
    }

    @PostMapping("/hold")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).SLOT_HOLD)")
    @Operation(
            summary = "Reserve a time while you pay",
            description = """
                    Holds the time and creates the appointment in `SLOT_HELD`.

                    **The hold expires.** `heldUntil` says when. If payment does not complete
                    the time returns to the pool and the appointment expires with it, which
                    is what stops abandoned payments quietly starving the schedule.

                    **Two patients pressing the same time at once: one wins.** The other
                    gets a clear message rather than both being told they have it. If the
                    exact slot goes between listing and holding, another room at the same
                    time is used automatically, so a patient is not told a time is gone when
                    it is not.

                    Pay next, through `/api/v1/payments/initiate`. Payment confirms the
                    booking and puts it on the Hub Coordinator's desk.

                    **Requires** `slot.hold`. Deliberately narrower than `slot.read`: an
                    Assistant Coordinator can see the times and only a Hub Coordinator or a
                    patient can take one.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Time held. Pay before it expires.",
                    content = @Content(schema = @Schema(implementation = ScheduleDtos.AppointmentResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Someone just took that time, or it has passed.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ScheduleDtos.AppointmentResponse> hold(
            @Valid @RequestBody ScheduleDtos.HoldSlotRequest request) {

        var patient = patientRepository.findById(CurrentUser.require().getPatientId())
                .orElseThrow(() -> new EntityNotFoundException("No patient record on this account"));

        // Consent and triage are checked here, not only in the app, so the
        // safety stop cannot be skipped by calling the API directly.
        triageService.requireClearedForBooking(patient.getId(), "FNPH_PATIENT");

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(toResponse(journey.submit(patient.getId(),
                        java.util.Optional.ofNullable(journey.current(patient.getId()))
                                .orElseThrow(() -> new IllegalArgumentException("Complete consultation information first")).publicId(),
                        request.slotPublicId())));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).APPOINTMENT_READ_OWN)")
    @Operation(
            summary = "My appointments",
            description = """
                    Every booking on this account, newest first.

                    **Show `SLOT_HELD` prominently with its countdown.** That is the state a
                    patient is in while paying, and one that expires silently looks like the
                    system lost their booking.

                    `EXPIRED` means payment did not complete in time. Nothing was charged.

                    **Requires** `appointment.read_own`.
                    """)
    @ApiResponse(responseCode = "200", description = "Appointments returned.")
    public ResponseEntity<List<ScheduleDtos.AppointmentResponse>> mine() {
        return ResponseEntity.ok(appointmentRepository
                .findAllByPatientIdOrderByAppointmentDateDesc(CurrentUser.require().getPatientId())
                .stream().map(this::toResponse).toList());
    }

    @GetMapping("/{appointmentPublicId}/history")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).APPOINTMENT_READ_OWN)")
    @Operation(
            summary = "What happened to this appointment",
            description = """
                    Every status change, oldest first, with who made it and why.

                    The appointment row holds only the current status. After a rejection
                    nothing on it says a request was ever made, so this is what answers
                    "what happened to my booking".

                    **Requires** `appointment.read_own`.
                    """)
    @ApiResponse(responseCode = "200", description = "History returned.")
    public ResponseEntity<List<ScheduleDtos.HistoryResponse>> history(
            @PathVariable String appointmentPublicId) {

        Appointment appointment = appointmentRepository.findByPublicId(appointmentPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such appointment"));

        // A patient sees their own. Anything else is not found, rather than
        // forbidden, so an identifier cannot be used to probe.
        Long patientId = CurrentUser.require().getPatientId();
        if (patientId != null && !patientId.equals(appointment.getPatient().getId())) {
            throw new EntityNotFoundException("No such appointment");
        }

        return ResponseEntity.ok(bookingService.historyFor(appointment.getId()).stream()
                .map(h -> new ScheduleDtos.HistoryResponse(
                        h.getFromStatus(), h.getToStatus(), h.getChangedBy(),
                        h.getChangedAt(), h.getReason()))
                .toList());
    }

    private ScheduleDtos.AppointmentResponse toResponse(Appointment a) {
        return new ScheduleDtos.AppointmentResponse(
                a.getPublicId(), a.getReference(), a.getStatus().name(),
                a.getAppointmentDate(), a.getScheduledEndAt(), a.getHeldUntil(),
                a.getRoom(), a.getJoinWindowOpensAt(), a.getRejectedReason());
    }
}
