package com.fnph.telepsychiatric.scheduling;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.appointment.AppointmentRepository;
import com.fnph.telepsychiatric.appointment.Status;
import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.configuration.ConfigurationKeys;
import com.fnph.telepsychiatric.configuration.ConfigurationService;
import com.fnph.telepsychiatric.notification.InAppNotificationService;
import com.fnph.telepsychiatric.notification.NotificationType;
import com.fnph.telepsychiatric.patient.Patient;
import com.fnph.telepsychiatric.payment.Payment;
import com.fnph.telepsychiatric.payment.PatientCreditService;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.security.crypto.Tokens;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The booking sequence, as confirmed by FNPH.
 *
 * <pre>
 *   1. Patient chooses a slot   -> slot HELD, appointment SLOT_HELD
 *   2. Patient pays             -> Remita, verified server-side
 *   3. Payment confirmed        -> wallet credited, slot BOOKED,
 *                                  appointment AWAITING_APPROVAL,
 *                                  Hub Coordinator dashboard notified
 *   4. Coordinator approves     -> doctor, nurse, room, pharmacy, laboratory
 *                                  and HIM assigned, wallet debited,
 *                                  every assignee notified
 *      or rejects               -> slot released, wallet balance kept
 * </pre>
 *
 * <h2>This inverts the order in the prototype document</h2>
 *
 * The PDF has payment at step 5 and slot selection at step 6. FNPH confirmed
 * the reverse, and it is the better order: a patient can see the time they are
 * buying, and nobody pays for something that turns out to be unavailable.
 *
 * It creates exactly one problem, and {@link SlotHold} is the answer. An unpaid
 * request would otherwise sit on a slot forever, and the schedule would show as
 * full while nobody was booked.
 *
 * <h2>Payment credits the wallet; approval spends it</h2>
 *
 * Money arrives before anyone approves anything. Crediting the wallet on
 * payment and debiting it on approval means a rejection simply never debits,
 * and the balance is there for the next attempt. Payment is non-refundable and
 * nobody is charged for a consultation that did not happen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final SlotRepository slotRepository;
    private final SlotHoldRepository holdRepository;
    private final AppointmentRepository appointmentRepository;
    private final AppointmentStatusHistoryRepository historyRepository;
    private final RoomRepository roomRepository;
    private final DoctorAvailabilityRepository availabilityRepository;
    private final PatientCreditService walletService;
    private final ConfigurationService configuration;
    private final InAppNotificationService notifications;
    private final AuditService auditService;

    // -----------------------------------------------------------------
    // Step 1: choose a time
    // -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Slot> bookableSlots(ScheduleAudience audience) {
        // Nothing inside the room-open lead time: a slot the patient could not
        // reach in time is worse than no slot, because they pay for it first.
        int lead = configuration.getInt(ConfigurationKeys.ROOM_OPEN_LEAD_MINUTES);
        return slotRepository.findBookable(audience, LocalDateTime.now().plusMinutes(lead));
    }

    /**
     * Holds a slot and creates the appointment in SLOT_HELD.
     *
     * The pessimistic lock is what makes two patients on the same time
     * deterministic. Optimistic locking alone would catch the conflict, but
     * only after both transactions had done their work, and the loser would see
     * a lock failure rather than "someone just took that time".
     */
    @Transactional
    public Appointment holdSlot(Patient patient, String slotPublicId) {
        Slot located = slotRepository.findByPublicId(slotPublicId)
                .orElseThrow(() -> new BookingException("That time is no longer listed"));

        Slot slot = slotRepository.findByIdForUpdate(located.getId())
                .orElseThrow(() -> new BookingException("That time is no longer listed"));

        if (!slot.isClaimable()) {
            throw new BookingException(
                    "Someone just took that time. Please choose another.");
        }
        if (slot.getStartAt().isBefore(LocalDateTime.now())) {
            throw new BookingException("That time has passed");
        }

        int holdMinutes = configuration.getInt(ConfigurationKeys.SLOT_HOLD_TTL_MINUTES);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusMinutes(holdMinutes);

        try {
            slot.setState(SlotState.HELD);
            slotRepository.save(slot);
        } catch (OptimisticLockingFailureException e) {
            throw new BookingException("Someone just took that time. Please choose another.");
        }

        SlotHold hold = new SlotHold();
        hold.setSlot(slot);
        hold.setHeldForPatient(patient);
        hold.setHeldAt(now);
        hold.setExpiresAt(expiresAt);
        holdRepository.save(hold);

        Appointment appointment = new Appointment();
        appointment.setPatient(patient);
        appointment.setSlot(slot);
        appointment.setReference("APT-" + Tokens.generateRecoveryCode().replace("-", ""));
        appointment.setAppointmentDate(slot.getStartAt());
        appointment.setScheduledEndAt(slot.getEndAt());
        appointment.setDurationMinutes(
                configuration.getInt(ConfigurationKeys.SESSION_MINUTES_FNPH));
        appointment.setStatus(Status.SLOT_HELD);
        appointment.setHeldUntil(expiresAt);
        Appointment saved = appointmentRepository.save(appointment);

        recordTransition(saved, null, Status.SLOT_HELD,
                "Slot held for %d minutes while payment completes".formatted(holdMinutes));

        log.info("Slot {} held for patient {} until {}",
                slot.getPublicId(), patient.getPublicId(), expiresAt);
        return saved;
    }

    // -----------------------------------------------------------------
    // Step 3: payment confirmed
    // -----------------------------------------------------------------

    /**
     * Called when a payment is verified. Confirms the booking and puts it on
     * the Hub Coordinator's desk.
     *
     * The slot moves to BOOKED, not merely held: the patient has paid and must
     * not lose the time while a coordinator works through a queue.
     */
    @Transactional
    public Appointment confirmPayment(Appointment appointment, Payment payment) {
        if (appointment.getStatus() != Status.SLOT_HELD) {
            return appointment;
        }

        Slot slot = appointment.getSlot();
        slot.setState(SlotState.BOOKED);
        slotRepository.save(slot);
        holdRepository.release(slot.getId(), LocalDateTime.now(), "Payment confirmed");

        appointment.setPayment(payment);
        appointment.setStatus(Status.AWAITING_APPROVAL);
        appointment.setHeldUntil(null);
        appointmentRepository.save(appointment);

        recordTransition(appointment, Status.SLOT_HELD, Status.AWAITING_APPROVAL,
                "Payment " + payment.getReference() + " confirmed");

        // The desk, not a named coordinator. Whoever is on duty picks it up and
        // it leaves everyone's list at once.
        notifications.notifyRole("HUB_COORDINATOR", null,
                NotificationType.BOOKING_AWAITING_APPROVAL,
                "Booking awaiting approval",
                "A paid consultation request is waiting for review and assignment for "
                        + appointment.getAppointmentDate().toLocalDate() + ".",
                "/hub/approvals/" + appointment.getPublicId(),
                "Appointment", appointment.getId());

        notifications.notifyPatient(appointment.getPatient(),
                NotificationType.BOOKING_SUBMITTED,
                "Your request is with the hospital",
                "Your payment is confirmed and your requested time is reserved. "
                        + "The hospital will confirm your appointment shortly.",
                "/portal/appointments/" + appointment.getPublicId(),
                "Appointment", appointment.getId());

        log.info("Appointment {} awaiting approval after payment {}",
                appointment.getReference(), payment.getReference());
        return appointment;
    }

    // -----------------------------------------------------------------
    // Step 4: the Hub Coordinator decides
    // -----------------------------------------------------------------

    /**
     * Approves, assigns the team and the room, debits the wallet, and tells
     * everyone who now has work.
     *
     * The assignment is validated before anything is committed. Assigning a
     * doctor who is on leave, or a room that is not a patient service room,
     * produces an appointment that looks confirmed to the patient and cannot
     * actually run.
     */
    @Transactional
    public Appointment approve(String appointmentPublicId, AssignmentRequest assignment) {
        Appointment appointment = appointmentRepository.findByPublicId(appointmentPublicId)
                .orElseThrow(() -> new BookingException("No such appointment"));

        if (appointment.getStatus() != Status.AWAITING_APPROVAL) {
            throw new BookingException(
                    "Only a paid request awaiting approval can be approved. This one is "
                            + appointment.getStatus() + ".");
        }

        if (assignment.doctor() == null) {
            throw new BookingException("A consulting doctor is required");
        }
        if (!availabilityRepository.isAvailable(assignment.doctor().getId(),
                appointment.getAppointmentDate(), appointment.getScheduledEndAt())) {
            throw new BookingException(
                    "That doctor is not marked available for the whole of that slot");
        }

        Room room = assignment.room();
        if (room == null || !Boolean.TRUE.equals(room.getIsActive())) {
            throw new BookingException("An active room is required");
        }
        if (room.getRoomType() == RoomType.CENTRE_CONSULTATION) {
            throw new BookingException(
                    "That room is reserved for centre consultations");
        }

        LocalDateTime now = LocalDateTime.now();
        String actor = CurrentUser.usernameOrSystem();

        appointment.setDoctor(assignment.doctor());
        appointment.setNurse(assignment.nurse());
        appointment.setPharmacist(assignment.pharmacist());
        appointment.setLaboratoryTechnician(assignment.laboratoryTechnician());
        appointment.setHimOfficer(assignment.himOfficer());
        appointment.setAssignedRoom(room);
        appointment.setRoom(room.getCode());
        appointment.setStatus(Status.APPROVED);
        appointment.setApprovedBy(actor);
        appointment.setApprovedAt(now);
        appointment.setJoinWindowOpensAt(appointment.getAppointmentDate()
                .minusMinutes(configuration.getInt(ConfigurationKeys.ROOM_OPEN_LEAD_MINUTES)));

        // Approval spends the wallet. Payment credited it; a rejection would
        // never have reached here, which is what leaves the balance intact.
        BigDecimal fee = configuration.getDecimal(ConfigurationKeys.CONSULTATION_FEE_NGN);
        walletService.apply(appointment.getPatient(), fee, appointment.getPayment() == null
                ? null : appointment.getPayment().getId());
        appointment.setWalletDebitedAt(now);

        appointmentRepository.save(appointment);
        recordTransition(appointment, Status.AWAITING_APPROVAL, Status.APPROVED,
                assignment.notes());

        notifyAssignees(appointment);

        notifications.notifyPatient(appointment.getPatient(),
                NotificationType.APPOINTMENT_APPROVED,
                "Your appointment is confirmed",
                "Your consultation is confirmed for %s in %s. You will be able to join "
                        .formatted(appointment.getAppointmentDate(), room.getName())
                        + "shortly before the start time.",
                "/portal/appointments/" + appointment.getPublicId(),
                "Appointment", appointment.getId());

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.RECORD_UPDATED)
                .entityType("Appointment")
                .entityId(appointment.getId())
                .details("Approved and assigned: doctor %s, room %s"
                        .formatted(assignment.doctor().getUsername(), room.getCode()))
                .reason(assignment.notes())
                .build());

        log.info("Appointment {} approved by {}: doctor {}, room {}",
                appointment.getReference(), actor,
                assignment.doctor().getUsername(), room.getCode());
        return appointment;
    }

    /**
     * Turns down a paid request.
     *
     * Releases the slot and leaves the amount on the patient's wallet. The
     * money stays with FNPH; the patient is not charged for a consultation that
     * did not happen and does not have to ask for anything.
     */
    @Transactional
    public Appointment reject(String appointmentPublicId, String reason) {
        Appointment appointment = appointmentRepository.findByPublicId(appointmentPublicId)
                .orElseThrow(() -> new BookingException("No such appointment"));

        if (appointment.getStatus() != Status.AWAITING_APPROVAL) {
            throw new BookingException("Only a request awaiting approval can be rejected");
        }

        Slot slot = appointment.getSlot();
        slot.setState(SlotState.AVAILABLE);
        slotRepository.save(slot);

        appointment.setStatus(Status.REJECTED);
        appointment.setRejectedReason(reason);
        appointmentRepository.save(appointment);

        recordTransition(appointment, Status.AWAITING_APPROVAL, Status.REJECTED, reason);

        // No wallet debit. The credit from the payment is untouched and applies
        // automatically to the next booking.
        BigDecimal balance = walletService.balanceFor(appointment.getPatient().getId());

        notifications.notifyPatient(appointment.getPatient(),
                NotificationType.APPOINTMENT_REJECTED,
                "Your requested appointment was not confirmed",
                "The hospital could not confirm that time. The amount you paid is held on "
                        + "your account and will be applied automatically when you book again. "
                        + "Current balance: NGN " + balance + ".",
                "/portal/booking", "Appointment", appointment.getId());

        log.info("Appointment {} rejected: {}. Wallet balance retained: {}",
                appointment.getReference(), reason, balance);
        return appointment;
    }

    // -----------------------------------------------------------------
    // Housekeeping
    // -----------------------------------------------------------------

    /**
     * Returns lapsed holds to the pool.
     *
     * Without this an abandoned payment holds a clinic slot indefinitely and
     * the schedule shows as full while nobody is booked. Run on a schedule.
     */
    @Transactional
    public int releaseLapsedHolds() {
        LocalDateTime now = LocalDateTime.now();
        int slots = slotRepository.releaseExpiredHolds(now);

        List<Appointment> lapsed = appointmentRepository.findLapsedHolds(now);
        lapsed.forEach(appointment -> {
            appointment.setStatus(Status.EXPIRED);
            appointmentRepository.save(appointment);
            recordTransition(appointment, Status.SLOT_HELD, Status.EXPIRED,
                    "Payment not completed before the hold expired");

            notifications.notifyPatient(appointment.getPatient(),
                    NotificationType.APPOINTMENT_CANCELLED,
                    "Your reserved time has been released",
                    "Payment was not completed in time, so the time you chose is available "
                            + "to others again. You can choose another time whenever you are ready.",
                    "/portal/booking", "Appointment", appointment.getId());
        });

        if (slots > 0 || !lapsed.isEmpty()) {
            log.info("Released {} lapsed slot holds and expired {} appointments",
                    slots, lapsed.size());
        }
        return lapsed.size();
    }

    @Transactional(readOnly = true)
    public List<AppointmentStatusHistory> historyFor(Long appointmentId) {
        return historyRepository.findAllByAppointmentIdOrderByChangedAtAsc(appointmentId);
    }

    // -----------------------------------------------------------------

    /**
     * Tells everyone who now has work.
     *
     * Addressed to the person, not the role: an assignment names a specific
     * clinician and only that clinician can act on it. The room-, date- and
     * time-specific notice is what the specification requires for the
     * multidisciplinary team.
     */
    private void notifyAssignees(Appointment appointment) {
        String when = appointment.getAppointmentDate().toString();
        String where = appointment.getRoom();
        String url = "/clinical/appointments/" + appointment.getPublicId();

        if (appointment.getDoctor() != null) {
            notifications.notifyUser(appointment.getDoctor(),
                    NotificationType.APPOINTMENT_APPROVED,
                    "You are assigned a consultation",
                    "Consultation assigned to you on %s in %s.".formatted(when, where),
                    url, "Appointment", appointment.getId());
        }
        if (appointment.getNurse() != null) {
            notifications.notifyUser(appointment.getNurse(),
                    NotificationType.APPOINTMENT_APPROVED,
                    "Preparation assigned to you",
                    "Vitals entry and room preparation for a consultation on %s in %s."
                            .formatted(when, where),
                    "/queues/nursing", "Appointment", appointment.getId());
        }
        if (appointment.getHimOfficer() != null) {
            notifications.notifyUser(appointment.getHimOfficer(),
                    NotificationType.APPOINTMENT_APPROVED,
                    "Record retrieval assigned to you",
                    "Offline record retrieval for a consultation on %s.".formatted(when),
                    "/queues/him", "Appointment", appointment.getId());
        }
        if (appointment.getPharmacist() != null) {
            notifications.notifyUser(appointment.getPharmacist(),
                    NotificationType.APPOINTMENT_APPROVED,
                    "You are on a consultation team",
                    "You are assigned to a consultation on %s in %s. Any prescription will "
                            .formatted(when, where) + "reach your queue after the session.",
                    "/reviews/pharmacy", "Appointment", appointment.getId());
        }
        if (appointment.getLaboratoryTechnician() != null) {
            notifications.notifyUser(appointment.getLaboratoryTechnician(),
                    NotificationType.APPOINTMENT_APPROVED,
                    "You are on a consultation team",
                    "You are assigned to a consultation on %s in %s. Any investigation "
                            .formatted(when, where) + "request will reach your queue after the session.",
                    "/reviews/laboratory", "Appointment", appointment.getId());
        }
    }

    private void recordTransition(Appointment appointment, Status from, Status to, String reason) {
        AppointmentStatusHistory entry = new AppointmentStatusHistory();
        entry.setAppointmentId(appointment.getId());
        entry.setFromStatus(from == null ? null : from.name());
        entry.setToStatus(to.name());
        entry.setChangedBy(CurrentUser.usernameOrSystem());
        entry.setChangedAt(LocalDateTime.now());
        entry.setReason(reason);
        historyRepository.save(entry);
    }

    /** The team and room a Hub Coordinator assigns at approval. */
    public record AssignmentRequest(
            com.fnph.telepsychiatric.user.Users doctor,
            com.fnph.telepsychiatric.user.Users nurse,
            com.fnph.telepsychiatric.user.Users pharmacist,
            com.fnph.telepsychiatric.user.Users laboratoryTechnician,
            com.fnph.telepsychiatric.user.Users himOfficer,
            Room room,
            String notes) {
    }

    public static class BookingException extends RuntimeException {
        public BookingException(String message) {
            super(message);
        }
    }
}
