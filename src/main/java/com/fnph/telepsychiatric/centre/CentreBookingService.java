package com.fnph.telepsychiatric.centre;

import com.fnph.telepsychiatric.appointment.CentreAppointment;
import com.fnph.telepsychiatric.appointment.CentreAppointmentRepository;
import com.fnph.telepsychiatric.appointment.Status;
import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.notification.InAppNotificationService;
import com.fnph.telepsychiatric.notification.NotificationType;
import com.fnph.telepsychiatric.patient.CentrePatient;
import com.fnph.telepsychiatric.patient.CentrePatientRepository;
import com.fnph.telepsychiatric.payment.WalletTransaction;
import com.fnph.telepsychiatric.scheduling.*;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.security.crypto.Tokens;
import com.fnph.telepsychiatric.user.Users;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Centre patient registration and the centre booking request.
 *
 * <h2>No payment step, and the wallet moves at approval</h2>
 *
 * A centre does not pay per booking at the point of request. The prepaid wallet
 * is debited when the Hub Coordinator approves, because that is when FNPH
 * commits a doctor, a room and a team to a slot no other centre can use.
 *
 * The consequence is that a centre request goes straight to
 * {@code AWAITING_APPROVAL} rather than passing through a held-and-paying
 * state. The slot is held only long enough to make the request.
 *
 * <h2>Funding is checked before anyone is assigned</h2>
 *
 * A booking approved against an empty wallet is a consultation the hospital
 * delivers and cannot account for. The check happens before the team is
 * assigned so the refusal is about money rather than a half-built appointment.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CentreBookingService {

    private final CentrePatientRepository patientRepository;
    private final CentreReferralRepository referralRepository;
    private final CentreAppointmentRepository appointmentRepository;
    private final SlotRepository slotRepository;
    private final RoomRepository roomRepository;
    private final CentreWalletService walletService;
    private final DoctorAvailabilityRepository availabilityRepository;
    private final InAppNotificationService notifications;
    private final AuditService auditService;

    @Transactional
    public CentrePatient registerPatient(Center centre, CentrePatient details) {
        details.setCentre(centre);
        details.setIsActive(true);

        if (details.getCentrePatientId() == null || details.getCentrePatientId().isBlank()) {
            // Centre-local, and unique per centre rather than globally. Two
            // centres numbering their patients from one is normal.
            details.setCentrePatientId(centre.getCode() + "-"
                    + Tokens.generateRecoveryCode().replace("-", "").substring(0, 6));
        }

        CentrePatient saved = patientRepository.save(details);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.RECORD_CREATED)
                .entityType("CentrePatient")
                .entityId(saved.getId())
                .details("Registered at " + centre.getCode())
                .build());

        return saved;
    }

    /** Times a centre can request. Drawn from the centre schedule, not the patient one. */
    @Transactional(readOnly = true)
    public List<Slot> availableSlots(java.time.LocalDate date) {
        return slotRepository.findAvailableOn(
                ScheduleAudience.CENTRE, date, LocalDateTime.now());
    }

    /**
     * Requests a consultation against a submitted referral.
     *
     * Goes straight to the Hub Coordinator. There is no payment step on this
     * pathway, so there is nothing to hold the slot for.
     */
    @Transactional
    public CentreAppointment requestAppointment(String referralPublicId, String slotPublicId) {
        CentreReferral referral = referralRepository.findByPublicId(referralPublicId)
                .orElseThrow(() -> new CentreBookingException("No such referral"));

        if (referral.getStatus() != ReferralStatus.SUBMITTED) {
            throw new CentreBookingException(
                    "Only a submitted referral can be scheduled. This one is "
                            + referral.getStatus() + ".");
        }

        Slot located = slotRepository.findByPublicId(slotPublicId)
                .orElseThrow(() -> new CentreBookingException("That time is no longer listed"));

        Slot slot = slotRepository.findByIdForUpdate(located.getId())
                .filter(Slot::isClaimable)
                .orElseThrow(() -> new CentreBookingException(
                        "Another centre just took that time. Please choose another."));

        slot.setState(SlotState.BOOKED);
        slotRepository.save(slot);

        CentreAppointment appointment = new CentreAppointment();
        appointment.setCentre(referral.getCentre());
        appointment.setCentrePatient(referral.getCentrePatient());
        appointment.setReferral(referral);
        appointment.setSlot(slot);
        appointment.setReference("CAP-" + Tokens.generateRecoveryCode().replace("-", ""));
        appointment.setAppointmentDateTime(slot.getStartAt());
        appointment.setScheduledEndAt(slot.getEndAt());
        appointment.setDurationMinutes(
                (int) java.time.Duration.between(slot.getStartAt(), slot.getEndAt()).toMinutes());
        appointment.setStatus(Status.AWAITING_APPROVAL);
        CentreAppointment saved = appointmentRepository.save(appointment);

        referral.setStatus(ReferralStatus.SCHEDULED);
        referralRepository.save(referral);

        notifications.notifyRole("HUB_COORDINATOR", null,
                NotificationType.BOOKING_AWAITING_APPROVAL,
                "Centre consultation request",
                "%s has requested a consultation for %s."
                        .formatted(referral.getCentre().getName(),
                                slot.getStartAt().toLocalDate()),
                "/hub/centre-approvals/" + saved.getPublicId(),
                "CentreAppointment", saved.getId());

        log.info("Centre {} requested consultation {} for {}",
                referral.getCentre().getCode(), saved.getReference(), slot.getStartAt());
        return saved;
    }

    /**
     * Approves a centre request, assigns the team and debits the wallet.
     *
     * Funding is checked before anything is assigned, so a refusal is about
     * money rather than a half-built appointment somebody then has to unpick.
     */
    @Transactional
    public CentreAppointment approve(String appointmentPublicId, Users doctor, Users pharmacist,
                                     Users laboratory, Users him, String roomPublicId,
                                     String notes) {
        CentreAppointment appointment = appointmentRepository.findByPublicId(appointmentPublicId)
                .orElseThrow(() -> new CentreBookingException("No such request"));

        if (appointment.getStatus() != Status.AWAITING_APPROVAL) {
            throw new CentreBookingException("Only a request awaiting approval can be approved");
        }
        if (doctor == null) {
            throw new CentreBookingException("A consulting doctor is required");
        }
        if (!availabilityRepository.isAvailable(doctor.getId(),
                appointment.getAppointmentDateTime(), appointment.getScheduledEndAt())) {
            throw new CentreBookingException(
                    "That doctor is not marked available for the whole of that slot");
        }

        Room room = roomRepository.findByPublicId(roomPublicId)
                .orElseThrow(() -> new CentreBookingException("No such room"));
        if (!Boolean.TRUE.equals(room.getIsActive())) {
            throw new CentreBookingException("That room is not in service");
        }

        // Money before people. Approving against an empty wallet is a
        // consultation the hospital delivers and cannot account for.
        WalletTransaction debit = walletService.debitForBooking(
                appointment.getCentre(), appointment.getId(), appointment.getReference());

        LocalDateTime now = LocalDateTime.now();
        appointment.setDoctor(doctor);
        appointment.setPharmacy(pharmacist);
        appointment.setLaboratory(laboratory);
        appointment.setHim(him);
        appointment.setRoomEntity(room);
        appointment.setRoom(room.getCode());
        appointment.setStatus(Status.APPROVED);
        appointment.setApprovedBy(CurrentUser.usernameOrSystem());
        appointment.setApprovedAt(now);
        appointment.setWalletTransactionId(debit.getId());
        appointment.setWalletDebitedAt(now);
        appointmentRepository.save(appointment);

        if (doctor != null) {
            notifications.notifyUser(doctor, NotificationType.APPOINTMENT_APPROVED,
                    "You are assigned a centre consultation",
                    "Hub-to-hub consultation on %s in %s."
                            .formatted(appointment.getAppointmentDateTime(), room.getName()),
                    "/clinical/centre/" + appointment.getPublicId(),
                    "CentreAppointment", appointment.getId());
        }

        notifications.notifyRole("CENTRE_HUB_COORDINATOR", appointment.getCentre(),
                NotificationType.APPOINTMENT_APPROVED,
                "Consultation confirmed",
                "Your requested consultation on %s is confirmed."
                        .formatted(appointment.getAppointmentDateTime()),
                "/centre/appointments/" + appointment.getPublicId(),
                "CentreAppointment", appointment.getId());

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.RECORD_UPDATED)
                .entityType("CentreAppointment")
                .entityId(appointment.getId())
                .details("Approved, doctor %s, room %s, wallet debited"
                        .formatted(doctor.getUsername(), room.getCode()))
                .reason(notes)
                .build());

        return appointment;
    }

    /**
     * Returns a request to the centre for more information.
     *
     * No wallet movement, because approval never happened. The referral goes
     * back to RETURNED so the centre can add what is missing and resubmit
     * rather than starting again.
     */
    @Transactional
    public CentreAppointment returnToCentre(String appointmentPublicId, String reason) {
        CentreAppointment appointment = appointmentRepository.findByPublicId(appointmentPublicId)
                .orElseThrow(() -> new CentreBookingException("No such request"));

        if (appointment.getStatus() != Status.AWAITING_APPROVAL) {
            throw new CentreBookingException("Only a pending request can be returned");
        }

        Slot slot = appointment.getSlot();
        if (slot != null) {
            slot.setState(SlotState.AVAILABLE);
            slotRepository.save(slot);
        }

        appointment.setStatus(Status.REJECTED);
        appointment.setReturnedReason(reason);
        appointmentRepository.save(appointment);

        CentreReferral referral = appointment.getReferral();
        if (referral != null) {
            referral.setStatus(ReferralStatus.RETURNED);
            referralRepository.save(referral);
        }

        notifications.notifyRole("CENTRE_HUB_COORDINATOR", appointment.getCentre(),
                NotificationType.APPOINTMENT_REJECTED,
                "Consultation request returned",
                reason,
                "/centre/referrals", "CentreAppointment", appointment.getId());

        return appointment;
    }

    public static class CentreBookingException extends RuntimeException {
        public CentreBookingException(String message) {
            super(message);
        }
    }
}
