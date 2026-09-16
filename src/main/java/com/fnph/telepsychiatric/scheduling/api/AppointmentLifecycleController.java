package com.fnph.telepsychiatric.scheduling.api;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.appointment.AppointmentRepository;
import com.fnph.telepsychiatric.appointment.Status;
import com.fnph.telepsychiatric.configuration.ConfigurationKeys;
import com.fnph.telepsychiatric.configuration.ConfigurationService;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.notification.InAppNotificationService;
import com.fnph.telepsychiatric.notification.NotificationType;
import com.fnph.telepsychiatric.scheduling.*;
import com.fnph.telepsychiatric.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Cancelling, rescheduling and recording a no-show.
 *
 * These had no endpoints, which left a patient who needed to move an
 * appointment with only the helpdesk, and a clinician facing an empty room with
 * no way to record it other than waiting for the session clock.
 */
@RestController
@RequestMapping("/api/v1/appointments")
@RequiredArgsConstructor
@Tag(name = "Appointment Lifecycle")
public class AppointmentLifecycleController {

    private final AppointmentRepository appointmentRepository;
    private final SlotRepository slotRepository;
    private final CancellationRequestRepository cancellationRepository;
    private final AppointmentStatusHistoryRepository historyRepository;
    private final ConfigurationService configuration;
    private final InAppNotificationService notifications;

    @PostMapping("/{appointmentPublicId}/cancel-request")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).APPOINTMENT_CANCEL)")
    @Operation(
            summary = "Ask to cancel or move an appointment",
            description = """
                    Records the request and how much notice was given.

                    **The request is recorded even when it is short notice.** A patient who
                    asked in time and was refused must be able to show that they asked, and
                    `hoursNotice` is the number the argument will be about.

                    The configured notice period is currently %s hours. Inside it the
                    request still reaches the Hub Coordinator rather than being rejected
                    automatically: a patient in hospital the morning of their appointment is
                    exactly the case a hard rule gets wrong.

                    **Nothing is refunded.** Payment is non-refundable, and an approved
                    cancellation leaves the amount on the patient's account for their next
                    booking.

                    **Requires** `appointment.cancel`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Request recorded."),
            @ApiResponse(responseCode = "400",
                    description = "The appointment has already happened or is not confirmed.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @Transactional
    public ResponseEntity<Map<String, Object>> requestCancellation(
            @PathVariable String appointmentPublicId,
            @Parameter(description = "CANCEL or RESCHEDULE.", example = "CANCEL", required = true)
            @RequestParam String requestType,
            @Parameter(description = "Why. Read by the coordinator.", required = true)
            @RequestParam String reason,
            @Parameter(description = "For a reschedule, the time you would prefer.")
            @RequestParam(required = false) String proposedSlotPublicId) {

        Appointment appointment = requireOwnIfPatient(appointmentPublicId);
        if (!"CANCEL".equals(requestType) && !"RESCHEDULE".equals(requestType)) {
            throw new IllegalArgumentException("Ask to CANCEL or to RESCHEDULE.");
        }

        if (appointment.getStatus() != Status.APPROVED
                && appointment.getStatus() != Status.AWAITING_APPROVAL) {
            throw new IllegalArgumentException(
                    "Only a pending or confirmed appointment can be cancelled. This one is "
                            + appointment.getStatus() + ".");
        }
        if (appointment.getAppointmentDate().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("That appointment has already passed");
        }

        int hoursNotice = (int) Duration.between(
                LocalDateTime.now(), appointment.getAppointmentDate()).toHours();

        CancellationRequest request = new CancellationRequest();
        request.setAppointment(appointment);
        request.setRequestType(requestType);
        request.setRequestedBy(CurrentUser.usernameOrSystem());
        request.setRequestedAt(LocalDateTime.now());
        request.setReason(reason);
        request.setHoursNotice(hoursNotice);
        if (proposedSlotPublicId != null && !proposedSlotPublicId.isBlank()) {
            slotRepository.findByPublicId(proposedSlotPublicId).ifPresent(request::setProposedSlot);
        }
        CancellationRequest saved = cancellationRepository.save(request);

        int required = configuration.getInt(ConfigurationKeys.CANCELLATION_NOTICE_HOURS);

        notifications.notifyRole("HUB_COORDINATOR", null, NotificationType.APPOINTMENT_CANCELLED,
                hoursNotice < required
                        ? "Short-notice cancellation request"
                        : "Cancellation request",
                "%s requested for %s with %d hours notice."
                        .formatted(requestType, appointment.getAppointmentDate().toLocalDate(),
                                hoursNotice),
                "/hub/cancellations/" + saved.getPublicId(),
                "CancellationRequest", saved.getId());

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "publicId", saved.getPublicId(),
                "hoursNotice", hoursNotice,
                "requiredNoticeHours", required,
                "withinNoticePeriod", hoursNotice >= required,
                "status", saved.getStatus()));
    }

    @GetMapping("/day")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).APPOINTMENT_READ)")
    @Transactional(readOnly = true)
    @Operation(summary = "One day's appointments",
            description = "The hub's view of a day, in hospital time. Recording a no-show or moving an "
                    + "appointment needs its id, and nothing listed confirmed appointments.")
    public ResponseEntity<List<Map<String, Object>>> day(
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate date) {
        LocalDateTime from = com.fnph.telepsychiatric.common.HospitalClock.toUtc(date, java.time.LocalTime.MIDNIGHT);
        return ResponseEntity.ok(appointmentRepository.findDay(from, from.plusDays(1)).stream().map(a -> {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("publicId", a.getPublicId());
            row.put("reference", a.getReference());
            row.put("status", a.getStatus().name());
            row.put("appointmentDate", a.getAppointmentDate());
            row.put("scheduledEndAt", a.getScheduledEndAt());
            row.put("room", a.getRoom());
            row.put("patientName", a.getPatient().getFirstName() + " " + a.getPatient().getLastName());
            row.put("ehrNumber", a.getPatient().getEhrNumber());
            row.put("doctorName", a.getDoctor() == null ? null : a.getDoctor().getFullName());
            return row;
        }).toList());
    }

    @GetMapping("/cancellations")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).APPOINTMENT_READ)")
    @Operation(
            summary = "Cancellation and reschedule requests awaiting a decision",
            description = """
                    Oldest first, with the notice given on each.

                    **Requires** `appointment.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Requests returned.")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> cancellations() {
        return ResponseEntity.ok(cancellationRepository
                .findAllByStatusOrderByRequestedAtAsc("SUBMITTED").stream().map(r -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("publicId", r.getPublicId());
                    row.put("appointmentReference", r.getAppointment().getReference());
                    row.put("appointmentPublicId", r.getAppointment().getPublicId());
                    row.put("appointmentDate", r.getAppointment().getAppointmentDate());
                    row.put("appointmentStatus", r.getAppointment().getStatus().name());
                    row.put("patientName", r.getAppointment().getPatient().getFirstName() + " "
                            + r.getAppointment().getPatient().getLastName());
                    row.put("requestedBy", r.getRequestedBy());
                    row.put("proposedTime", r.getProposedSlot() == null ? null : r.getProposedSlot().getStartAt());
                    row.put("requestType", r.getRequestType());
                    row.put("reason", r.getReason());
                    row.put("hoursNotice", r.getHoursNotice());
                    row.put("requestedAt", r.getRequestedAt());
                    return row;
                }).toList());
    }

    @PostMapping("/cancellations/{requestPublicId}/decide")
    // Deciding is the hub's call. appointment.cancel is also a patient
    // permission, so under it a patient could approve their own request.
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).APPOINTMENT_APPROVE)")
    @Operation(
            summary = "Approve or refuse a cancellation",
            description = """
                    Approving releases the slot and cancels the appointment. The amount paid
                    stays on the patient's account and applies to their next booking, because
                    payment is non-refundable and a patient should not be charged twice for
                    one consultation.

                    Refusing records the decision and the reason. The appointment stands.

                    **Requires** `appointment.cancel`.
                    """)
    @ApiResponse(responseCode = "204", description = "Decided.")
    @Transactional
    public ResponseEntity<Void> decide(@PathVariable String requestPublicId,
                                       @RequestParam boolean approve,
                                       @RequestParam String notes) {

        CancellationRequest request = cancellationRepository.findByPublicId(requestPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such request"));
        if (!"SUBMITTED".equals(request.getStatus())) {
            throw new IllegalStateException("That request has already been decided");
        }
        if (!approve && (notes == null || notes.isBlank())) {
            throw new IllegalArgumentException("Say why, so the patient knows what to do next.");
        }

        request.setStatus(approve ? "APPROVED" : "REFUSED");
        request.setDecidedBy(CurrentUser.usernameOrSystem());
        request.setDecidedAt(LocalDateTime.now());
        request.setDecisionNotes(notes);
        cancellationRepository.save(request);

        Appointment appointment = request.getAppointment();

        if (approve) {
            Slot slot = appointment.getSlot();
            if (slot != null) {
                slot.setState(SlotState.AVAILABLE);
                slotRepository.save(slot);
            }
            transition(appointment, Status.CANCELLED, notes);

            notifications.notifyPatient(appointment.getPatient(),
                    NotificationType.APPOINTMENT_CANCELLED,
                    "Your appointment has been cancelled",
                    "The amount you paid is held on your account and will be applied "
                            + "automatically when you book again.",
                    "/portal/booking", "Appointment", appointment.getId());
        } else {
            notifications.notifyPatient(appointment.getPatient(),
                    NotificationType.APPOINTMENT_CANCELLED,
                    "Your cancellation request was not approved",
                    notes,
                    "/portal/appointments", "Appointment", appointment.getId());
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{appointmentPublicId}/reschedule")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).APPOINTMENT_RESCHEDULE)")
    @Operation(
            summary = "Move an appointment to another time",
            description = """
                    Releases the old slot, takes the new one and keeps the same appointment
                    and payment.

                    **The team assignment is cleared.** The doctor who was available at the
                    old time may not be at the new one, so the appointment returns to
                    `AWAITING_APPROVAL` and the coordinator reassigns. Carrying the
                    assignment across would produce an appointment that looks confirmed and
                    has nobody able to run it.

                    Nothing is charged again.

                    **Requires** `appointment.reschedule`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Moved, awaiting reassignment."),
            @ApiResponse(responseCode = "400", description = "The new time is taken.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @Transactional
    public ResponseEntity<Map<String, Object>> reschedule(
            @PathVariable String appointmentPublicId,
            @RequestParam String newSlotPublicId,
            @RequestParam String reason) {

        Appointment appointment = requireOwnIfPatient(appointmentPublicId);
        if (appointment.getStatus() != Status.APPROVED
                && appointment.getStatus() != Status.AWAITING_APPROVAL) {
            throw new IllegalStateException("Only a pending or confirmed appointment can be moved. This one is "
                    + appointment.getStatus() + ".");
        }
        if (appointment.getAppointmentDate().isBefore(LocalDateTime.now())) {
            throw new IllegalStateException("That appointment has already passed");
        }

        Slot located = slotRepository.findByPublicId(newSlotPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such time"));
        // Patient times only. A centre slot belongs to the centre schedule.
        if (located.getPublication() == null
                || located.getPublication().getAudience()
                        != ScheduleAudience.FNPH_PATIENT) {
            throw new IllegalArgumentException("That time is not open to patients. Choose another.");
        }
        Slot target = slotRepository.findByIdForUpdate(located.getId())
                .filter(Slot::isClaimable)
                .orElseThrow(() -> new IllegalArgumentException(
                        "That time has been taken. Choose another."));

        Slot previous = appointment.getSlot();
        if (previous != null) {
            previous.setState(SlotState.AVAILABLE);
            slotRepository.save(previous);
        }

        target.setState(SlotState.BOOKED);
        slotRepository.save(target);

        appointment.setSlot(target);
        appointment.setAppointmentDate(target.getStartAt());
        appointment.setScheduledEndAt(target.getEndAt());
        // Cleared deliberately. Availability is per doctor per window.
        appointment.setDoctor(null);
        appointment.setNurse(null);
        appointment.setPharmacist(null);
        appointment.setLaboratoryTechnician(null);
        appointment.setHimOfficer(null);
        appointment.setAssignedRoom(null);
        appointment.setRoom(null);
        appointment.setJoinWindowOpensAt(null);
        appointment.setStatus(Status.AWAITING_APPROVAL);
        appointmentRepository.save(appointment);

        transition(appointment, Status.AWAITING_APPROVAL, "Rescheduled: " + reason);

        notifications.notifyRole("HUB_COORDINATOR", null,
                NotificationType.BOOKING_AWAITING_APPROVAL,
                "Rescheduled appointment needs reassignment",
                "Moved to %s. The team assignment was cleared because availability is per "
                        .formatted(target.getStartAt()) + "doctor per window.",
                "/hub/approvals/" + appointment.getPublicId(),
                "Appointment", appointment.getId());

        return ResponseEntity.ok(Map.of(
                "reference", appointment.getReference(),
                "newTime", appointment.getAppointmentDate(),
                "status", appointment.getStatus().name()));
    }

    @PostMapping("/{appointmentPublicId}/no-show")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).APPOINTMENT_MARK_NO_SHOW)")
    @Operation(
            summary = "Record that the patient did not attend",
            description = """
                    For a clinician who waited and the patient never joined.

                    The session clock records a no-show automatically at the slot end, so
                    this is for recording it earlier with a note, which frees the clinician
                    rather than leaving them holding a room.

                    **Nothing is refunded and nothing is credited.** The consultation slot
                    was held, a team was committed and a room was reserved. A no-show costs
                    the hospital exactly what an attended session does.

                    **Requires** `appointment.mark_no_show`.
                    """)
    @ApiResponse(responseCode = "204", description = "Recorded.")
    @Transactional
    public ResponseEntity<Void> noShow(@PathVariable String appointmentPublicId,
                                       @RequestParam String notes) {
        Appointment appointment = require(appointmentPublicId);
        if (appointment.getStatus() != Status.APPROVED && appointment.getStatus() != Status.IN_PROGRESS) {
            throw new IllegalStateException("Only a confirmed appointment can be recorded as not attended");
        }
        if (appointment.getAppointmentDate().isAfter(LocalDateTime.now())) {
            throw new IllegalStateException("That appointment has not started yet");
        }

        appointment.setStatus(Status.NO_SHOW);
        appointment.setNoShowAt(LocalDateTime.now());
        appointment.setNoShowReason(notes);
        appointmentRepository.save(appointment);

        transition(appointment, Status.NO_SHOW, notes);

        notifications.notifyPatient(appointment.getPatient(),
                NotificationType.APPOINTMENT_CANCELLED,
                "You did not attend your appointment",
                "The appointment was recorded as not attended. Contact the hospital to "
                        + "arrange another.",
                "/portal/appointments", "Appointment", appointment.getId());

        return ResponseEntity.noContent().build();
    }

    private void transition(Appointment appointment, Status to, String reason) {
        AppointmentStatusHistory entry = new AppointmentStatusHistory();
        entry.setAppointmentId(appointment.getId());
        entry.setToStatus(to.name());
        entry.setChangedBy(CurrentUser.usernameOrSystem());
        entry.setChangedAt(LocalDateTime.now());
        entry.setReason(reason);
        historyRepository.save(entry);
    }

    /**
     * The appointment, and for a patient only their own. Patients hold the
     * cancel and reschedule permissions, and without this could act on anyone's
     * appointment by id. Same answer as a missing appointment.
     */
    private Appointment requireOwnIfPatient(String publicId) {
        Appointment appointment = require(publicId);
        Long patientId = CurrentUser.get().map(u -> u.getPatientId()).orElse(null);
        if (patientId != null && !patientId.equals(appointment.getPatient().getId())) {
            throw new EntityNotFoundException("No such appointment");
        }
        return appointment;
    }

    private Appointment require(String publicId) {
        return appointmentRepository.findByPublicId(publicId)
                .orElseThrow(() -> new EntityNotFoundException("No such appointment"));
    }
}
