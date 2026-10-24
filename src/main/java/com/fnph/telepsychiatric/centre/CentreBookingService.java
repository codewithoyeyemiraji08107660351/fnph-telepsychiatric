package com.fnph.telepsychiatric.centre;

import com.fnph.telepsychiatric.appointment.CentreAppointment;
import com.fnph.telepsychiatric.appointment.CentreAppointmentRepository;
import com.fnph.telepsychiatric.appointment.Status;
import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.common.HospitalClock;
import com.fnph.telepsychiatric.hub.HubTeamService.Role;
import com.fnph.telepsychiatric.notification.InAppNotificationService;
import com.fnph.telepsychiatric.notification.NotificationType;
import com.fnph.telepsychiatric.patient.CentrePatient;
import com.fnph.telepsychiatric.patient.CentrePatientRepository;
import com.fnph.telepsychiatric.payment.WalletTransaction;
import com.fnph.telepsychiatric.scheduling.*;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.security.crypto.Tokens;
import com.fnph.telepsychiatric.tenancy.TenantContext;
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
    private final DoctorCalendar doctorCalendar;
    private final com.fnph.telepsychiatric.hub.HubTeamService teamService;
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
               CentreReferral referral = referralRepository
                .findByCentreIdAndPublicId(TenantContext.requireCentreId(), referralPublicId)
                .orElseThrow(() -> new CentreBookingException("No such referral"));

        // A suspended centre must not keep booking on referrals it sent while
        // active; its bundles would have nobody to receive them.
        if (referral.getCentre().getStatus() != com.fnph.telepsychiatric.center.CentreStatus.ACTIVE) {
            throw new CentreBookingException(
                    "This centre is not active, so consultations cannot be requested. Contact FNPH.");
        }
        if (referral.getStatus() != ReferralStatus.SUBMITTED) {
            throw new CentreBookingException(
                    "Only a submitted referral can be scheduled. This one is "
                            + referral.getStatus() + ".");
        }

        Slot located = slotRepository.findByPublicId(slotPublicId)
                .orElseThrow(() -> new CentreBookingException("That time is no longer listed"));

        // The picker shows one row per time. If this room was taken first, any
        // other centre room free at the same time will do.
        Slot slot = slotRepository.findByIdForUpdate(located.getId())
                .filter(Slot::isClaimable)
                .or(() -> sameTimeElsewhere(located))
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
        appointment.setAppointmentDate(slot.getStartAt());
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
                                HospitalClock.display(slot.getStartAt())),
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
        // Approving a time that has started charges the wallet for a session
        // nobody can join any more.
        if (!appointment.getAppointmentDate().isAfter(LocalDateTime.now())) {
            throw new CentreBookingException(
                    "That time has already started. Return the request so the centre can "
                            + "choose another time.");
        }
        if (doctor == null) {
            throw new CentreBookingException("A consulting doctor is required");
        }
        teamService.assertEligible(doctor, Role.DOCTOR);
        if (!availabilityRepository.isAvailable(doctor.getId(),
                appointment.getAppointmentDate(), appointment.getScheduledEndAt())) {
            throw new CentreBookingException(
                    "That doctor is not marked available for the whole of that slot");
        }
        if (doctorCalendar.busy(doctor.getId(), appointment.getAppointmentDate(),
                appointment.getScheduledEndAt(), null, appointment.getId())) {
            throw new CentreBookingException(doctor.getFullName()
                    + " already has a consultation at that time, on the FNPH or centre pathway");
        }
        // The rest of the team are optional, but whoever is named must hold the
        // role. Any account used to be accepted, including a patient's.
        if (pharmacist != null) {
            teamService.assertEligible(pharmacist, Role.PHARMACIST);
        }
        if (laboratory != null) {
            teamService.assertEligible(laboratory, Role.LABORATORY);
        }
        if (him != null) {
            teamService.assertEligible(him, Role.HIM);
        }

        Room room = roomRepository.findByPublicId(roomPublicId)
                .orElseThrow(() -> new CentreBookingException("No such room"));
        if (!Boolean.TRUE.equals(room.getIsActive())) {
            throw new CentreBookingException("That room is not in service");
        }
        if (room.getRoomType() != RoomType.CENTRE_CONSULTATION) {
            throw new CentreBookingException("That room is not a centre consultation room");
        }
        if (appointmentRepository.roomBusy(room.getId(), appointment.getId(),
                appointment.getAppointmentDate(), appointment.getScheduledEndAt())) {
            throw new CentreBookingException(
                    room.getName() + " is already booked for another centre consultation then");
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
                            .formatted(HospitalClock.display(appointment.getAppointmentDate()), room.getName()),
                    "/clinical/centre/" + appointment.getPublicId(),
                    "CentreAppointment", appointment.getId());
        }

        notifications.notifyRole("CENTRE_HUB_COORDINATOR", appointment.getCentre(),
                NotificationType.APPOINTMENT_APPROVED,
                "Consultation confirmed",
                "Your requested consultation on %s is confirmed."
                        .formatted(HospitalClock.display(appointment.getAppointmentDate())),
                "/centre/consultations",
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

    // -----------------------------------------------------------------
    // Cancellation and expiry
    // -----------------------------------------------------------------

    /** The FNPH hub cancels a centre request or confirmed consultation. */
    @Transactional
    public CentreAppointment cancelByHub(String appointmentPublicId, String reason) {
        CentreAppointment appointment = appointmentRepository.findByPublicId(appointmentPublicId)
                .orElseThrow(() -> new CentreBookingException("No such consultation"));
        return cancel(appointment, reason, false);
    }

    /** The centre cancels its own request or confirmed consultation. */
    @Transactional
    public CentreAppointment cancelByCentre(String appointmentPublicId, String reason) {
        CentreAppointment appointment = appointmentRepository
                .findByCentreIdAndPublicId(TenantContext.requireCentreId(), appointmentPublicId)
                .orElseThrow(() -> new CentreBookingException("No such consultation"));
        return cancel(appointment, reason, true);
    }

    /**
     * Cancels before the start time and returns the wallet charge.
     *
     * There was no cancellation at all: once approved, a consultation that
     * could no longer happen still held its doctor, its room and the centre's
     * money. The refund is the full charge whichever side cancels, as long as
     * it is before the start; after that the session clock decides.
     *
     * The referral goes back to SUBMITTED so the centre can request another
     * time without entering it again.
     */
    private CentreAppointment cancel(CentreAppointment appointment, String reason, boolean byCentre) {
        if (appointment.getStatus() != Status.AWAITING_APPROVAL
                && appointment.getStatus() != Status.APPROVED) {
            throw new CentreBookingException(
                    "Only a pending or confirmed consultation can be cancelled. This one is "
                            + appointment.getStatus() + ".");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!appointment.getAppointmentDate().isAfter(now)) {
            throw new CentreBookingException(
                    "That consultation has already started. It closes at its end time, and a "
                            + "missed session is refunded automatically when the doctor did not join.");
        }
        if (reason == null || reason.trim().length() < 5) {
            throw new CentreBookingException("Say why it is being cancelled.");
        }
        String why = reason.trim();

        boolean wasApproved = appointment.getStatus() == Status.APPROVED;
        appointment.setStatus(Status.CANCELLED);
        appointment.setCancelledAt(now);
        appointment.setCancelledBy(CurrentUser.usernameOrSystem());
        appointment.setCancellationReason(why);

        WalletTransaction reversal = walletService.reverseBookingDebit(appointment,
                (byCentre ? "Cancelled by the centre: " : "Cancelled by FNPH: ") + why);
        if (reversal != null) {
            appointment.setWalletReversalId(reversal.getId());
            appointment.setWalletReversedAt(now);
        }
        appointmentRepository.save(appointment);

        releaseSlot(appointment);
        reopenReferral(appointment);

        String when = HospitalClock.display(appointment.getAppointmentDate());
        if (byCentre) {
            notifications.notifyRole("HUB_COORDINATOR", null, NotificationType.APPOINTMENT_CANCELLED,
                    "Centre consultation cancelled by the centre",
                    "%s cancelled %s on %s: %s".formatted(appointment.getCentre().getName(),
                            appointment.getReference(), when, why),
                    "/hub/centre-approvals", "CentreAppointment", appointment.getId());
        } else {
            notifications.notifyRole("CENTRE_HUB_COORDINATOR", appointment.getCentre(),
                    NotificationType.APPOINTMENT_CANCELLED,
                    "Consultation cancelled by FNPH",
                    "%s on %s was cancelled: %s. Request another time from the referral."
                            .formatted(appointment.getReference(), when, why),
                    "/centre/consultations", "CentreAppointment", appointment.getId());
        }
        if (wasApproved && appointment.getDoctor() != null) {
            notifications.notifyUser(appointment.getDoctor(), NotificationType.APPOINTMENT_CANCELLED,
                    "Centre consultation cancelled",
                    "%s on %s at %s is cancelled.".formatted(appointment.getReference(), when,
                            appointment.getCentre().getName()),
                    "/clinical", "CentreAppointment", appointment.getId());
        }

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.APPOINTMENT_UPDATED)
                .entityType("CentreAppointment")
                .entityId(appointment.getId())
                .details("Cancelled by %s%s".formatted(byCentre ? "the centre" : "FNPH",
                        reversal != null ? ", wallet charge returned" : ""))
                .reason(why)
                .build());
        return appointment;
    }

    /**
     * Requests nobody decided before their time came.
     *
     * They held their slot and showed as pending forever. Expired, the slot is
     * released, the referral reopens so the centre can choose another time,
     * and the centre is told. No money moved, because approval never happened.
     */
    @Transactional
    public int expireUndecided(LocalDateTime now) {
        List<CentreAppointment> stale = appointmentRepository.findAwaitingApprovalStartedBy(now);
        for (CentreAppointment appointment : stale) {
            appointment.setStatus(Status.EXPIRED);
            appointmentRepository.save(appointment);
            releaseSlot(appointment);
            reopenReferral(appointment);
            notifications.notifyRole("CENTRE_HUB_COORDINATOR", appointment.getCentre(),
                    NotificationType.SLOT_HOLD_EXPIRED,
                    "Consultation request not decided in time",
                    "FNPH did not decide %s for %s before it started. Request another time from the referral."
                            .formatted(appointment.getReference(),
                                    HospitalClock.display(appointment.getAppointmentDate())),
                    "/centre/referrals", "CentreAppointment", appointment.getId());
            log.info("Centre request {} expired undecided", appointment.getReference());
        }
        return stale.size();
    }

    private java.util.Optional<Slot> sameTimeElsewhere(Slot taken) {
        return slotRepository.findAvailableOn(ScheduleAudience.CENTRE, taken.getPublication().getServiceDate(),
                        LocalDateTime.now()).stream()
                .filter(s -> s.getStartAt().equals(taken.getStartAt()) && s.getEndAt().equals(taken.getEndAt()))
                .filter(s -> !s.getId().equals(taken.getId()))
                .map(s -> slotRepository.findByIdForUpdate(s.getId()).filter(Slot::isClaimable))
                .flatMap(java.util.Optional::stream)
                .findFirst();
    }

    private void releaseSlot(CentreAppointment appointment) {
        Slot slot = appointment.getSlot();
        if (slot != null && slot.getState() == SlotState.BOOKED) {
            slot.setState(SlotState.AVAILABLE);
            slotRepository.save(slot);
        }
    }

    private void reopenReferral(CentreAppointment appointment) {
        CentreReferral referral = appointment.getReferral();
        if (referral != null && referral.getStatus() == ReferralStatus.SCHEDULED) {
            referral.setStatus(ReferralStatus.SUBMITTED);
            referralRepository.save(referral);
        }
    }

    public static class CentreBookingException extends RuntimeException {
        public CentreBookingException(String message) {
            super(message);
        }
    }
}
