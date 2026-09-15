package com.fnph.telepsychiatric.consultation;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.appointment.AppointmentRepository;
import com.fnph.telepsychiatric.appointment.Status;
import com.fnph.telepsychiatric.notification.InAppNotificationService;
import com.fnph.telepsychiatric.notification.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The database half of the FNPH consultation clock.
 *
 * <h2>Why this is a separate bean</h2>
 *
 * A @Transactional method called from inside the same class bypasses Spring's
 * proxy and runs with no transaction, silently. {@link
 * ConsultationService#tick()} has to be non-transactional so it can call Daily
 * outside a transaction, so the transactional work has to live on a different
 * bean to be real.
 *
 * <h2>What is slow and what is not</h2>
 *
 * The only slow call in the clock is deleting the room at Daily, whose read
 * timeout is twenty seconds. Inside a transaction that holds a connection from
 * a pool of twenty for the duration, and a dozen expired rooms on a bad
 * connection is four minutes. The symptom is the whole application stalling
 * with a scheduled job as the cause and nothing in the request logs pointing at
 * it.
 *
 * The warnings are not slow. InAppNotificationService writes a row; it does not
 * send mail. So the warning flag and the notice that records it sit in the same
 * transaction and are always consistent: if it rolls back, neither happened.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConsultationClockSteps {

    private final ConsultationRepository consultationRepository;
    private final AppointmentRepository appointmentRepository;
    private final AttendanceEventRepository attendanceRepository;
    private final InAppNotificationService notifications;

    /**
     * @param ended         how many consultations were closed
     * @param roomsToDelete rooms to remove at the provider once this
     *                      transaction has committed
     */
    public record ClockResult(int ended, List<RoomRef> roomsToDelete) { }

    /** A room that still exists at the provider and the row that owns it. */
    public record RoomRef(Long consultationId, String roomName, String why) { }

    /**
     * Advances every running consultation: warnings, counters and closure.
     *
     * Returns the rooms needing deletion rather than deleting them, because
     * that is the one part that must not happen with a transaction open.
     */
    @Transactional
    public ClockResult advanceRunning(LocalDateTime now, int warnOne, int warnTwo) {
        List<Consultation> running = consultationRepository.findRunning(now);
        List<RoomRef> rooms = new ArrayList<>();
        int ended = 0;

        for (Consultation consultation : running) {
            long remaining = Duration.between(now, consultation.getScheduledEndAt())
                    .getSeconds();
            consultation.setRemainingSeconds((int) Math.max(0, remaining));

            if (remaining <= 0) {
                closeAtSlotEnd(consultation, now);
                if (needsRoomDeletion(consultation)) {
                    rooms.add(new RoomRef(consultation.getId(),
                            consultation.getRoomName(), "Slot end reached"));
                }
                ended++;
                continue;
            }

            long minutesLeft = remaining / 60;
            if (minutesLeft <= warnOne && consultation.getWarningOneSentAt() == null) {
                consultation.setWarningOneSentAt(now);
                warn(consultation, warnOne);
            }
            if (minutesLeft <= warnTwo && consultation.getWarningTwoSentAt() == null) {
                consultation.setWarningTwoSentAt(now);
                warn(consultation, warnTwo);
            }
            consultationRepository.save(consultation);
        }
        return new ClockResult(ended, rooms);
    }

    /**
     * Rooms left open past their expiry, including any the provider refused to
     * delete on an earlier pass.
     *
     * Read-only, and ids plus names only, so nothing is detached here and then
     * lazily loaded after the transaction closes.
     */
    @Transactional(readOnly = true)
    public List<RoomRef> expiredRooms(LocalDateTime now) {
        return consultationRepository.findRoomsToClose(now).stream()
                .filter(this::needsRoomDeletion)
                .map(c -> new RoomRef(c.getId(), c.getRoomName(), "Room expiry reached"))
                .toList();
    }

    /** After the provider confirmed the room is gone. */
    @Transactional
    public void markRoomDeleted(Long consultationId, String why) {
        consultationRepository.findById(consultationId).ifPresent(consultation -> {
            if (consultation.getRoomDeletedAt() != null) {
                return;
            }
            consultation.setRoomDeletedAt(LocalDateTime.now());
            consultationRepository.save(consultation);
            recordAttendance(consultation, ParticipantRole.DOCTOR,
                    AttendanceEventType.ROOM_CLOSED, null, why);
        });
    }

    private boolean needsRoomDeletion(Consultation consultation) {
        return consultation.getRoomName() != null && consultation.getRoomDeletedAt() == null;
    }

    /**
     * Closes the clinical record. The room is dealt with afterwards.
     *
     * The consultation is closed whether or not the provider call later
     * succeeds. A Daily outage must not leave a finished consultation showing
     * as running, and the undeleted room is logged as the separate problem it
     * is.
     */
    private void closeAtSlotEnd(Consultation consultation, LocalDateTime now) {
        boolean patientJoined = consultation.getPatientJoinedAt() != null;

        consultation.setEndedAt(now);
        consultation.setOutcome(patientJoined ? Outcome.COMPLETED : Outcome.NO_SHOW);
        consultationRepository.save(consultation);

        Appointment appointment = consultation.getAppointment();
        if (appointment != null) {
            appointment.setStatus(patientJoined ? Status.COMPLETED : Status.NO_SHOW);
            if (!patientJoined) {
                appointment.setNoShowAt(now);
                appointment.setNoShowReason("Patient did not join before the session ended");
            }
            appointmentRepository.save(appointment);
        }
        log.info("Consultation {} closed at slot end, outcome {}",
                consultation.getPublicId(), consultation.getOutcome());
    }

    /**
     * Only the clinician is warned on this pathway.
     *
     * The patient sees the countdown in the browser, and a countdown
     * notification on a psychiatric consultation would add pressure to the one
     * person who should not feel it.
     */
    private void warn(Consultation consultation, int minutes) {
        recordAttendance(consultation, ParticipantRole.DOCTOR,
                AttendanceEventType.WARNING_SENT, null,
                "%d minutes remaining".formatted(minutes));

        if (consultation.getDoctor() != null) {
            notifications.notifyUser(consultation.getDoctor(),
                    NotificationType.CONSULTATION_READY,
                    "%d minutes remaining".formatted(minutes),
                    "This consultation ends at its scheduled time.",
                    "/clinical/consultations/" + consultation.getPublicId(),
                    "Consultation", consultation.getId());
        }
    }

    private void recordAttendance(Consultation consultation, ParticipantRole role,
                                  AttendanceEventType type, String modality, String details) {
        AttendanceEvent event = new AttendanceEvent();
        event.setConsultationId(consultation.getId());
        event.setParticipantRole(role);
        event.setEventType(type);
        event.setModality(modality);
        event.setOccurredAt(LocalDateTime.now());
        event.setDetails(details);
        attendanceRepository.save(event);
    }
}