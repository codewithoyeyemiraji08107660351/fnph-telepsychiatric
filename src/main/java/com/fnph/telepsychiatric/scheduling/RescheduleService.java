package com.fnph.telepsychiatric.scheduling;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.appointment.AppointmentRepository;
import com.fnph.telepsychiatric.appointment.Status;
import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.common.BusinessRuleException;
import com.fnph.telepsychiatric.common.HospitalClock;
import com.fnph.telepsychiatric.configuration.ConfigurationKeys;
import com.fnph.telepsychiatric.configuration.ConfigurationService;
import com.fnph.telepsychiatric.notification.InAppNotificationService;
import com.fnph.telepsychiatric.notification.NotificationType;
import com.fnph.telepsychiatric.patient.PatientIntake;
import com.fnph.telepsychiatric.patient.PatientIntakeRepository;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.security.crypto.Tokens;
import com.fnph.telepsychiatric.upload.FileUploadRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Moving an appointment to a new time.
 *
 * <h2>Two cases, two shapes</h2>
 *
 * <b>Not yet held</b> (awaiting approval, including one whose time passed
 * before the Hub Coordinator could approve it): the same appointment moves.
 * Nothing has happened at the old time, so there is nothing to keep.
 *
 * <b>Missed</b> (the patient did not attend): a new appointment is created
 * and points back at the missed one. The missed appointment's consultation
 * row, attendance and outcome are a record of what happened, and moving it
 * in place would overwrite them on the next join.
 *
 * <h2>Money</h2>
 *
 * Nothing is charged again in either case. A moved request keeps its payment
 * and is debited once, at approval. A rebooked missed appointment was already
 * debited when it was first approved, so the new one carries that debit and
 * approval does not take it a second time. Rebooking is the coordinator's
 * decision, made with a reason, and recorded.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RescheduleService {

    /** History rows keep 500 characters. */
    private static final int HISTORY_REASON_MAX = 500;

    /** Recognised by the hub dashboard as a reschedule; keep the prefix. */
    static final String HISTORY_PREFIX = "Rescheduled: ";

    private final AppointmentRepository appointmentRepository;
    private final SlotRepository slotRepository;
    private final AppointmentStatusHistoryRepository historyRepository;
    private final TeamHistoryService teamHistory;
    private final ConfigurationService configuration;
    private final InAppNotificationService notifications;
    private final PatientIntakeRepository intakes;
    private final FileUploadRepository files;
    private final AuditService auditService;

    /**
     * Moves a pending or confirmed appointment to a new time.
     *
     * @param byStaff true for the Hub Coordinator. Staff may move a request
     *                whose time has already passed without approval; a
     *                patient may only move one that is still ahead.
     */
    @Transactional
    public Appointment move(Appointment appointment, String newSlotPublicId, String reason,
                            boolean byStaff) {
        String why = requireReason(reason);
        Status status = appointment.getStatus();
        if (status != Status.APPROVED && status != Status.AWAITING_APPROVAL) {
            throw new BusinessRuleException("Only a pending or confirmed appointment can be moved. "
                    + "This one is " + status + ".");
        }
        boolean passed = !appointment.getAppointmentDate().isAfter(LocalDateTime.now());
        if (passed && !(byStaff && status == Status.AWAITING_APPROVAL)) {
            throw new BusinessRuleException("That appointment has already passed");
        }

        Slot target = claim(newSlotPublicId);
        LocalDateTime oldTime = appointment.getAppointmentDate();

        Slot previous = appointment.getSlot();
        if (previous != null && !previous.getId().equals(target.getId())) {
            previous.setState(SlotState.AVAILABLE);
            slotRepository.save(previous);
        }

        TeamHistoryService.TeamSnapshot teamBefore = TeamHistoryService.TeamSnapshot.of(appointment);
        place(appointment, target);
        // Cleared deliberately. Availability is per doctor per window, so the
        // team chosen for the old time may not be free at the new one.
        clearTeam(appointment);
        appointment.setStatus(Status.AWAITING_APPROVAL);
        appointmentRepository.save(appointment);

        history(appointment, status, Status.AWAITING_APPROVAL, HISTORY_PREFIX
                + "from " + HospitalClock.display(oldTime) + ". " + why);
        teamHistory.record(appointment, teamBefore,
                AppointmentTeamEvent.ChangeSource.RESCHEDULE, why);

        if (byStaff) {
            notifications.notifyPatient(appointment.getPatient(),
                    NotificationType.APPOINTMENT_RESCHEDULED,
                    "Your appointment has a new time",
                    "The hospital moved your consultation to %s. You will get another message "
                            .formatted(HospitalClock.display(target.getStartAt()))
                            + "when the doctor and room are confirmed. Nothing more is charged.",
                    "/portal/appointments", "Appointment", appointment.getId());
        } else {
            notifications.notifyRole("HUB_COORDINATOR", null,
                    NotificationType.BOOKING_AWAITING_APPROVAL,
                    "Rescheduled appointment needs reassignment",
                    "Moved to %s. The team assignment was cleared because availability is per "
                            .formatted(HospitalClock.display(target.getStartAt()))
                            + "doctor per window.",
                    "/hub/approvals/" + appointment.getPublicId(),
                    "Appointment", appointment.getId());
        }

        audit(appointment, "Moved from %s to %s".formatted(
                HospitalClock.display(oldTime), HospitalClock.display(target.getStartAt())), why);
        return appointment;
    }

    /**
     * Gives a patient who missed their consultation a new time, without a
     * new payment. Hub Coordinator only.
     */
    @Transactional
    public Appointment rebookMissed(String missedPublicId, String newSlotPublicId, String reason) {
        String why = requireReason(reason);
        Appointment missed = appointmentRepository.findByPublicId(missedPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such appointment"));
        if (missed.getStatus() != Status.NO_SHOW) {
            throw new BusinessRuleException("Only a missed appointment can be rebooked this way. "
                    + "This one is " + missed.getStatus() + ".");
        }
        if (appointmentRepository.existsByRescheduledFrom(missed)) {
            throw new BusinessRuleException("This missed appointment has already been rebooked");
        }

        Slot target = claim(newSlotPublicId);

        Appointment next = new Appointment();
        next.setPatient(missed.getPatient());
        next.setPayment(missed.getPayment());
        next.setReason(missed.getReason());
        next.setReference("APT-" + Tokens.generateRecoveryCode().replace("-", ""));
        next.setRescheduledFrom(missed);
        // Already charged when the missed appointment was approved. Carrying
        // the date across is what stops approval charging it again.
        next.setWalletDebitedAt(missed.getWalletDebitedAt() != null
                ? missed.getWalletDebitedAt() : LocalDateTime.now());
        place(next, target);
        next.setStatus(Status.AWAITING_APPROVAL);
        next = appointmentRepository.save(next);

        carryIntake(missed, next);

        String note = HISTORY_PREFIX + "rebooked after missed appointment "
                + missed.getReference() + " on " + HospitalClock.display(missed.getAppointmentDate())
                + ". " + why;
        history(next, null, Status.AWAITING_APPROVAL, note);
        // No history row on the missed appointment: any row ending in NO_SHOW
        // counts as another no-show on the hub dashboard. The link back
        // (rescheduled_from_id) and the audit entry record the rebooking.

        notifications.notifyPatient(missed.getPatient(),
                NotificationType.APPOINTMENT_RESCHEDULED,
                "A new time for your missed consultation",
                "The hospital booked you a new consultation for %s. Nothing more is charged. "
                        .formatted(HospitalClock.display(target.getStartAt()))
                        + "You will get another message when the doctor and room are confirmed.",
                "/portal/appointments", "Appointment", next.getId());

        audit(next, "Rebooked missed appointment %s for %s".formatted(
                missed.getReference(), HospitalClock.display(target.getStartAt())), why);
        log.info("Missed appointment {} rebooked as {} for {}", missed.getReference(),
                next.getReference(), target.getStartAt());
        return next;
    }

    /** Missed appointments in the last {@code days} days not yet rebooked. */
    @Transactional(readOnly = true)
    public List<Appointment> missedNotRebooked(int days) {
        int window = Math.max(1, Math.min(days, 90));
        return appointmentRepository.findMissedNotRebooked(LocalDateTime.now().minusDays(window));
    }

    /**
     * Locks and takes a patient time. Only an open FNPH patient slot that
     * starts after the room-open lead, the same rule patients book under.
     */
    private Slot claim(String slotPublicId) {
        if (slotPublicId == null || slotPublicId.isBlank()) {
            throw new IllegalArgumentException("Choose the new time");
        }
        Slot located = slotRepository.findByPublicId(slotPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such time"));
        if (located.getPublication() == null
                || located.getPublication().getAudience() != ScheduleAudience.FNPH_PATIENT) {
            throw new IllegalArgumentException("That time is not open to patients. Choose another.");
        }
        int lead = configuration.getInt(ConfigurationKeys.ROOM_OPEN_LEAD_MINUTES);
        if (!located.getStartAt().isAfter(LocalDateTime.now().plusMinutes(lead))) {
            throw new IllegalArgumentException("That time is too soon or has passed. Choose a later one.");
        }
        Slot target = slotRepository.findByIdForUpdate(located.getId())
                .filter(Slot::isClaimable)
                .orElseThrow(() -> new IllegalArgumentException(
                        "That time has been taken. Choose another."));
        target.setState(SlotState.BOOKED);
        return slotRepository.save(target);
    }

    private static void place(Appointment appointment, Slot slot) {
        appointment.setSlot(slot);
        appointment.setAppointmentDate(slot.getStartAt());
        appointment.setScheduledEndAt(slot.getEndAt());
        appointment.setDurationMinutes((int) java.time.Duration
                .between(slot.getStartAt(), slot.getEndAt()).toMinutes());
    }

    private static void clearTeam(Appointment appointment) {
        appointment.setDoctor(null);
        appointment.setNurse(null);
        appointment.setPharmacist(null);
        appointment.setLaboratoryTechnician(null);
        appointment.setHimOfficer(null);
        appointment.setAssignedRoom(null);
        appointment.setRoom(null);
        appointment.setJoinWindowOpensAt(null);
        appointment.setApprovedAt(null);
        appointment.setApprovedBy(null);
    }

    /**
     * The intake travels with the request: the doctor at the new time needs
     * the reason for the visit and the files the patient sent. The missed
     * appointment keeps its own copy of the intake.
     */
    private void carryIntake(Appointment missed, Appointment next) {
        intakes.findByAppointmentPublicId(missed.getPublicId()).ifPresent(original -> {
            PatientIntake copy = new PatientIntake();
            copy.setPatientId(original.getPatientId());
            copy.setPayload(original.getPayload());
            copy.setAppointmentPublicId(next.getPublicId());
            intakes.save(copy);
        });
        files.findAllByReferenceIdOrderByUploadedAtDesc(missed.getPublicId()).forEach(file -> {
            file.setReferenceId(next.getPublicId());
            files.save(file);
        });
    }

    private void history(Appointment appointment, Status from, Status to, String reason) {
        AppointmentStatusHistory entry = new AppointmentStatusHistory();
        entry.setAppointmentId(appointment.getId());
        entry.setFromStatus(from == null ? null : from.name());
        entry.setToStatus(to.name());
        entry.setChangedBy(CurrentUser.usernameOrSystem());
        entry.setChangedAt(LocalDateTime.now());
        entry.setReason(reason.length() > HISTORY_REASON_MAX
                ? reason.substring(0, HISTORY_REASON_MAX - 1) + "…" : reason);
        historyRepository.save(entry);
    }

    private void audit(Appointment appointment, String details, String reason) {
        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.RECORD_UPDATED)
                .entityType("Appointment")
                .entityId(appointment.getId())
                .details(details)
                .reason(reason)
                .build());
    }

    private static String requireReason(String reason) {
        String why = reason == null ? "" : reason.trim();
        if (why.length() < 5) {
            throw new IllegalArgumentException("Give a reason of at least 5 characters. It is kept on the record.");
        }
        return why;
    }
}
