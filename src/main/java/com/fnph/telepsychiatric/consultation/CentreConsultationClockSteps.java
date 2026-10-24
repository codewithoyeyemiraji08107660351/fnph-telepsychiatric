package com.fnph.telepsychiatric.consultation;

import com.fnph.telepsychiatric.appointment.CentreAppointment;
import com.fnph.telepsychiatric.appointment.CentreAppointmentRepository;
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
 * The database half of the centre session clock.
 *
 * Mirrors {@link ConsultationClockSteps} for the centre tables. It used to
 * reuse the FNPH steps, which read FNPH consultations, so a centre session was
 * never warned, never closed at its slot end and its video room was never
 * deleted. The appointment then sat at IN_PROGRESS indefinitely.
 *
 * A separate bean so its transactions are real when called from
 * {@link CentreConsultationService#tick()}.
 *
 * <h2>Who did not turn up</h2>
 *
 * The FNPH clock has one question at slot end: did the patient join. A centre
 * session has two parties who can be absent, and the answer decides who owes
 * whom. The outcome records which one, in words the Hub Coordinator can act on
 * without opening the attendance log:
 *
 * <ul>
 *   <li>both joined: COMPLETED</li>
 *   <li>the doctor joined, the centre did not: NO_SHOW, the centre's absence</li>
 *   <li>the doctor never joined: NO_SHOW on the appointment, ABANDONED on the
 *       session, and the wallet charge is returned, because the hospital did
 *       not deliver what the centre paid for</li>
 * </ul>
 *
 * An appointment nobody joined is refunded on the same rule: the doctor did
 * not join either. A centre that did not turn up for a doctor who did is not
 * refunded.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CentreConsultationClockSteps {

    static final String CENTRE_ABSENT =
            "The centre did not join with the patient before the session ended";
    static final String DOCTOR_ABSENT =
            "The consulting doctor did not join before the session ended";
    static final String NOBODY_JOINED =
            "Nobody joined before the session ended";

    private final CentreConsultationRepository consultationRepository;
    private final CentreAppointmentRepository appointmentRepository;
    private final AttendanceEventRepository attendanceRepository;
    private final InAppNotificationService notifications;
    private final com.fnph.telepsychiatric.centre.CentreWalletService walletService;
    private final com.fnph.telepsychiatric.centre.CentreReferralRepository referralRepository;

    /** Warnings for running sessions, and the hard close at slot end. */
    @Transactional
    public ConsultationClockSteps.ClockResult advanceRunning(LocalDateTime now, int warnOne, int warnTwo) {
        List<CentreConsultation> running = consultationRepository.findRunning(now);
        List<ConsultationClockSteps.RoomRef> rooms = new ArrayList<>();
        int ended = 0;

        for (CentreConsultation consultation : running) {
            long remaining = Duration.between(now, consultation.getScheduledEndAt()).getSeconds();
            consultation.setRemainingSeconds((int) Math.max(0, remaining));

            if (remaining <= 0) {
                closeAtSlotEnd(consultation, now);
                if (needsRoomDeletion(consultation)) {
                    rooms.add(new ConsultationClockSteps.RoomRef(consultation.getId(),
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
        return new ConsultationClockSteps.ClockResult(ended, rooms);
    }

    /**
     * Approved appointments whose slot ended with no session at all.
     *
     * The session row is created on the first join from either side, so an
     * appointment with none means nobody joined. Without this it stays
     * APPROVED forever and keeps showing as upcoming.
     */
    @Transactional
    public int closeUnattended(LocalDateTime now) {
        List<CentreAppointment> missed = appointmentRepository.findApprovedEndedWithoutSession(now);
        for (CentreAppointment appointment : missed) {
            appointment.setStatus(Status.NO_SHOW);
            appointment.setNoShowAt(now);
            appointment.setNoShowReason(NOBODY_JOINED);
            boolean refunded = refund(appointment, NOBODY_JOINED, now);
            appointmentRepository.save(appointment);
            referralAfter(appointment, false);
            tellHub(appointment, NOBODY_JOINED, refunded);
            log.info("Centre appointment {} closed unattended", appointment.getReference());
        }
        return missed.size();
    }

    @Transactional(readOnly = true)
    public List<ConsultationClockSteps.RoomRef> expiredRooms(LocalDateTime now) {
        return consultationRepository.findExpiredRooms(now).stream()
                .filter(this::needsRoomDeletion)
                .map(c -> new ConsultationClockSteps.RoomRef(c.getId(), c.getRoomName(), "Room expiry reached"))
                .toList();
    }

    @Transactional
    public void markRoomDeleted(Long consultationId, String why) {
        consultationRepository.findById(consultationId).ifPresent(consultation -> {
            if (consultation.getRoomDeletedAt() != null) {
                return;
            }
            consultation.setRoomDeletedAt(LocalDateTime.now());
            consultationRepository.save(consultation);
            recordAttendance(consultation, AttendanceEventType.ROOM_CLOSED, why);
        });
    }

    // -----------------------------------------------------------------

    private void closeAtSlotEnd(CentreConsultation consultation, LocalDateTime now) {
        boolean doctorJoined = consultation.getStartedAt() != null;
        boolean centreJoined = attendanceRepository.existsByCentreConsultationIdAndParticipantRoleAndEventType(
                consultation.getId(), ParticipantRole.CENTRE, AttendanceEventType.JOINED);

        consultation.setEndedAt(now);
        consultation.setOutcome(!doctorJoined ? Outcome.ABANDONED
                : centreJoined ? Outcome.COMPLETED : Outcome.NO_SHOW);
        consultationRepository.save(consultation);

        CentreAppointment appointment = consultation.getCentreAppointment();
        if (appointment == null) {
            return;
        }
        if (doctorJoined && centreJoined) {
            appointment.setStatus(Status.COMPLETED);
            referralAfter(appointment, true);
        } else {
            String reason = !doctorJoined ? DOCTOR_ABSENT : CENTRE_ABSENT;
            appointment.setStatus(Status.NO_SHOW);
            appointment.setNoShowAt(now);
            appointment.setNoShowReason(reason);
            boolean refunded = !doctorJoined && refund(appointment, reason, now);
            tellHub(appointment, reason, refunded);
            referralAfter(appointment, false);
        }
        appointmentRepository.save(appointment);
        log.info("Centre consultation {} closed at slot end, outcome {}",
                consultation.getPublicId(), consultation.getOutcome());
    }

    /**
     * Moves the referral on once the session is decided.
     *
     * A held session completes it. A missed one reopens it, so the centre can
     * request another time from the same referral. It used to stay SCHEDULED
     * either way, so the centre's "completed" count was always zero and a
     * missed patient could not be rebooked without a new referral.
     */
    private void referralAfter(CentreAppointment appointment, boolean held) {
        var referral = appointment.getReferral();
        if (referral == null || referral.getStatus() != com.fnph.telepsychiatric.centre.ReferralStatus.SCHEDULED) {
            return;
        }
        referral.setStatus(held ? com.fnph.telepsychiatric.centre.ReferralStatus.COMPLETED
                : com.fnph.telepsychiatric.centre.ReferralStatus.SUBMITTED);
        referralRepository.save(referral);
    }

    /** Returns the charge; true when money moved. Idempotent in the wallet service. */
    private boolean refund(CentreAppointment appointment, String reason, LocalDateTime now) {
        var reversal = walletService.reverseBookingDebit(appointment, reason);
        if (reversal == null) {
            return false;
        }
        appointment.setWalletReversalId(reversal.getId());
        appointment.setWalletReversedAt(now);
        return true;
    }

    private void tellHub(CentreAppointment appointment, String reason, boolean refunded) {
        notifications.notifyRole("HUB_COORDINATOR", null, NotificationType.NO_SHOW_RECORDED,
                "Centre consultation did not take place",
                "%s, %s. %s. %s"
                        .formatted(appointment.getCentre().getName(), appointment.getReference(), reason,
                                refunded ? "The centre's wallet charge has been returned."
                                        : "The wallet charge stands."),
                "/hub/centre-approvals", "CentreAppointment", appointment.getId());
        notifications.notifyRole("CENTRE_HUB_COORDINATOR", appointment.getCentre(),
                NotificationType.NO_SHOW_RECORDED,
                "Consultation did not take place",
                "%s. %s. Contact the FNPH hub to arrange another time."
                        .formatted(appointment.getReference(), reason),
                "/centre/consultations", "CentreAppointment", appointment.getId());
    }

    private boolean needsRoomDeletion(CentreConsultation consultation) {
        return consultation.getRoomName() != null && consultation.getRoomDeletedAt() == null;
    }

    private void warn(CentreConsultation consultation, int minutes) {
        recordAttendance(consultation, AttendanceEventType.WARNING_SENT,
                "%d minutes remaining".formatted(minutes));
        if (consultation.getDoctor() != null && consultation.getCentreAppointment() != null) {
            notifications.notifyUser(consultation.getDoctor(),
                    NotificationType.CONSULTATION_READY,
                    "%d minutes remaining".formatted(minutes),
                    "This centre consultation ends at its scheduled time.",
                    "/clinical/centre/" + consultation.getCentreAppointment().getPublicId(),
                    "CentreConsultation", consultation.getId());
        }
    }

    private void recordAttendance(CentreConsultation consultation, AttendanceEventType type, String details) {
        AttendanceEvent event = new AttendanceEvent();
        event.setCentreConsultationId(consultation.getId());
        event.setParticipantRole(ParticipantRole.DOCTOR);
        event.setEventType(type);
        event.setOccurredAt(LocalDateTime.now());
        event.setDetails(details);
        attendanceRepository.save(event);
    }
}
