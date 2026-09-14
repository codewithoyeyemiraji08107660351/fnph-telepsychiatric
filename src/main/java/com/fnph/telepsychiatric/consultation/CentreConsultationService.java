package com.fnph.telepsychiatric.consultation;

import com.fnph.telepsychiatric.appointment.CentreAppointment;
import com.fnph.telepsychiatric.appointment.CentreAppointmentRepository;
import com.fnph.telepsychiatric.appointment.Status;
import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.configuration.ConfigurationKeys;
import com.fnph.telepsychiatric.configuration.ConfigurationService;
import com.fnph.telepsychiatric.consultation.video.DailyProperties;
import com.fnph.telepsychiatric.consultation.video.VideoProvider;
import com.fnph.telepsychiatric.notification.InAppNotificationService;
import com.fnph.telepsychiatric.notification.NotificationType;
import com.fnph.telepsychiatric.security.crypto.Tokens;
import com.fnph.telepsychiatric.tenancy.TenantContext;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * The centre consultation lifecycle.
 *
 * <h2>How this differs from the FNPH pathway</h2>
 *
 * <b>Two participants, not three.</b> The patient is physically at the centre,
 * in front of a coordinator, so there is no patient join. The coordinator joins
 * as CENTRE and the clinician as DOCTOR.
 *
 * <b>The clinician owns the call.</b> Same reasoning as the FNPH pathway: a
 * participant who could end the session could end it for the doctor. The centre
 * is a participant regardless of the coordinator's seniority.
 *
 * <b>The cutoff applies to the centre, not the patient.</b> On the FNPH pathway
 * a patient who misses the window is told to rebook. Here the centre is
 * responsible for having the patient in the room, so the cutoff lands on the
 * CENTRE role.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CentreConsultationService {

    private final CentreConsultationRepository consultationRepository;
    private final CentreAppointmentRepository appointmentRepository;
    private final ParticipantTokenRepository tokenRepository;
    private final AttendanceEventRepository attendanceRepository;
    private final VideoProvider videoProvider;
    private final DailyProperties videoProperties;
    private final ConfigurationService configuration;
    private final InAppNotificationService notifications;
    private final AuditService auditService;

    // -----------------------------------------------------------------
    // Room
    // -----------------------------------------------------------------

    /**
     * The room is created on first join, not at approval, for the same reason
     * as the FNPH pathway: a room created days ahead sits open at the provider
     * and most would never be used because appointments get cancelled.
     */
    @Transactional
    public CentreConsultation ensureRoom(CentreAppointment appointment) {
        return consultationRepository.findByCentreAppointmentId(appointment.getId())
                .filter(c -> c.getRoomName() != null && c.getRoomDeletedAt() == null)
                .orElseGet(() -> createRoom(appointment));
    }

    private CentreConsultation createRoom(CentreAppointment appointment) {
        if (appointment.getDoctor() == null) {
            throw new ConsultationService.ConsultationException(
                    "No clinician is assigned to that appointment yet");
        }

        CentreConsultation consultation = consultationRepository
                .findByCentreAppointmentId(appointment.getId())
                .orElseGet(CentreConsultation::new);

        consultation.setCentre(appointment.getCentre());
        consultation.setCentreAppointment(appointment);
        consultation.setCentrePatient(appointment.getCentrePatient());
        consultation.setDoctor(appointment.getDoctor());
        consultation.setScheduledStartAt(appointment.getAppointmentDate());
        consultation.setScheduledEndAt(appointment.getScheduledEndAt());
        consultation.setModality(Modality.VIDEO);

        LocalDateTime roomExpiry = appointment.getScheduledEndAt()
                .plusMinutes(videoProperties.getRoomGraceMinutes());

        boolean recording = configuration.getBoolean(ConfigurationKeys.RECORDING_ENABLED);

        // "centre-" rather than "fnph-", so a room name identifies its pathway
        // when support is looking at the provider dashboard.
        String roomName = "centre-" + appointment.getReference().toLowerCase();
        VideoProvider.VideoRoom room = videoProvider.createRoom(roomName, roomExpiry, recording);

        consultation.setRoomName(room.name());
        consultation.setRoomUrl(room.url());
        consultation.setRoomProviderId(room.providerId());
        consultation.setRoomExpiresAt(roomExpiry);
        consultation.setRoomCreatedAt(LocalDateTime.now());

        CentreConsultation saved = consultationRepository.save(consultation);
        log.info("Centre video room {} created for centre appointment {}",
                room.name(), appointment.getReference());
        return saved;
    }

    // -----------------------------------------------------------------
    // Joining
    // -----------------------------------------------------------------

    /**
     * Issues a join token for one participant on the centre pathway.
     *
     * @param role DOCTOR or CENTRE. PATIENT is rejected: on this pathway the
     *             patient attends in person at the centre.
     */
    @Transactional
    public ConsultationService.JoinDetails join(String appointmentPublicId,
                                                ParticipantRole role,
                                                String ipAddress) {
        if (role == ParticipantRole.PATIENT) {
            throw new ConsultationService.ConsultationException(
                    "On the centre pathway the patient attends at the centre. There is no "
                            + "patient join.");
        }

        CentreAppointment appointment = resolveAppointment(appointmentPublicId, role);

        if (appointment.getStatus() != Status.APPROVED
                && appointment.getStatus() != Status.IN_PROGRESS) {
            throw new ConsultationService.ConsultationException(
                    "That appointment is not confirmed, so there is nothing to join");
        }

        LocalDateTime now = LocalDateTime.now();
        int lead = configuration.getInt(ConfigurationKeys.ROOM_OPEN_LEAD_MINUTES);
        int cutoff = configuration.getInt(ConfigurationKeys.NO_SHOW_CUTOFF_MINUTES);

        LocalDateTime opensAt = appointment.getAppointmentDate().minusMinutes(lead);
        LocalDateTime centreCutoff = appointment.getAppointmentDate().plusMinutes(cutoff);

        if (now.isBefore(opensAt)) {
            throw new ConsultationService.ConsultationException(
                    "The consultation room opens %d minutes before the appointment."
                            .formatted(lead));
        }
        if (now.isAfter(appointment.getScheduledEndAt())) {
            throw new ConsultationService.ConsultationException("That consultation has ended");
        }
        // The centre is responsible for having the patient present, so the
        // cutoff lands here. A clinician arriving late must still be able to
        // enter and record the no-show.
        if (role == ParticipantRole.CENTRE && now.isAfter(centreCutoff)) {
            throw new ConsultationService.ConsultationException(
                    ("The join window closed %d minutes after the start time. Contact the hub "
                            + "to rebook.").formatted(cutoff));
        }

        CentreConsultation consultation = ensureRoom(appointment);

        boolean owner = role == ParticipantRole.DOCTOR;
        String displayName = displayNameFor(appointment, role);

        String rawToken = videoProvider.createParticipantToken(
                consultation.getRoomName(), displayName, owner, opensAt,
                appointment.getScheduledEndAt()
                        .plusMinutes(videoProperties.getRoomGraceMinutes()));

        ParticipantToken token = new ParticipantToken();
        token.setCentreConsultation(consultation);
        // Tenant key. Without it the filter hides the token from the centre
        // that needs it.
        token.setCentre(appointment.getCentre());
        token.setParticipantRole(role);
        token.setTokenHash(Tokens.hash(rawToken));
        token.setDisplayName(displayName);
        token.setNotBefore(opensAt);
        token.setExpiresAt(appointment.getScheduledEndAt()
                .plusMinutes(videoProperties.getRoomGraceMinutes()));
        token.setIssuedAt(now);
        token.setIssuedIp(ipAddress);
        if (owner) {
            token.setUser(appointment.getDoctor());
        }
        tokenRepository.save(token);

        recordAttendance(consultation, role, AttendanceEventType.JOINED, null, null);

        if (owner && consultation.getStartedAt() == null) {
            consultation.setStartedAt(now);
            appointment.setStatus(Status.IN_PROGRESS);
            appointmentRepository.save(appointment);
        }
        consultationRepository.save(consultation);

        long remaining = Duration.between(now, appointment.getScheduledEndAt()).getSeconds();

        return new ConsultationService.JoinDetails(
                consultation.getPublicId(),
                consultation.getRoomUrl(),
                rawToken,
                consultation.getScheduledStartAt(),
                consultation.getScheduledEndAt(),
                Math.max(remaining, 0),
                configuration.getInt(ConfigurationKeys.WARNING_ONE_MINUTES_REMAINING),
                configuration.getInt(ConfigurationKeys.WARNING_TWO_MINUTES_REMAINING),
                owner);
    }

    /**
     * A centre principal may only reach its own appointment. The doctor is
     * hospital-scoped and reaches any.
     */
    private CentreAppointment resolveAppointment(String publicId, ParticipantRole role) {
        if (role == ParticipantRole.CENTRE) {
            Long centreId = TenantContext.current().centreId();
            return appointmentRepository.findByCentreIdAndPublicId(centreId, publicId)
                    .orElseThrow(() -> new EntityNotFoundException("No such consultation"));
        }
        return appointmentRepository.findByPublicId(publicId)
                .orElseThrow(() -> new EntityNotFoundException("No such consultation"));
    }

    private String displayNameFor(CentreAppointment appointment, ParticipantRole role) {
        return role == ParticipantRole.DOCTOR
                ? "Consultant"
                : appointment.getCentre().getName();
    }

    private void recordAttendance(CentreConsultation consultation, ParticipantRole role,
                                  AttendanceEventType type, String modality, String details) {
        AttendanceEvent event = new AttendanceEvent();
        event.setCentreConsultationId(consultation.getId());
        event.setParticipantRole(role);
        event.setEventType(type);
        event.setModality(modality);
        event.setOccurredAt(LocalDateTime.now());
        event.setDetails(details);
        attendanceRepository.save(event);
    }


    /**
     * Countdown warnings and close at slot end. Run every 30 seconds.
     *
     * Module 3 fixes the slot and requires the 15-minute warning. Both warning
     * thresholds are shared with the FNPH pathway because they are clinical
     * timings, not pathway timings.
     */
    @Transactional
    public int tick() {
        LocalDateTime now = LocalDateTime.now();
        int warnOne = configuration.getInt(ConfigurationKeys.WARNING_ONE_MINUTES_REMAINING);
        int warnTwo = configuration.getInt(ConfigurationKeys.WARNING_TWO_MINUTES_REMAINING);

        int ended = 0;
        for (CentreConsultation consultation : consultationRepository.findRunning(now)) {
            long remaining = Duration.between(now, consultation.getScheduledEndAt()).getSeconds();
            consultation.setRemainingSeconds((int) Math.max(0, remaining));

            if (remaining <= 0) {
                closeAtSlotEnd(consultation);
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
        return ended;
    }

    /**
     * Both ends are warned here, unlike the FNPH pathway.
     *
     * On the FNPH pathway only the clinician is notified; the patient sees the
     * countdown in the browser. The centre coordinator is running the room with
     * the patient in it, so they need the same warning the clinician gets.
     */
    private void warn(CentreConsultation consultation, int minutes) {
        recordAttendance(consultation, ParticipantRole.DOCTOR,
                AttendanceEventType.WARNING_SENT, null,
                "%d minutes remaining".formatted(minutes));

        String subject = "%d minutes remaining".formatted(minutes);
        String body = "This consultation ends at its scheduled time.";
        String url = "/clinical/centre/consultations/" + consultation.getPublicId();

        if (consultation.getDoctor() != null) {
            notifications.notifyUser(consultation.getDoctor(),
                    NotificationType.CONSULTATION_READY, subject, body, url,
                    "CentreConsultation", consultation.getId());
        }
        notifications.notifyRole("CENTRE_HUB_COORDINATOR", consultation.getCentre(),
                NotificationType.CONSULTATION_READY, subject, body,
                "/centre/consultations/" + consultation.getPublicId(),
                "CentreConsultation", consultation.getId());
    }

    /**
     * A centre that never joined is the no-show, not the patient.
     *
     * The patient attended in person; if nobody at the centre brought them into
     * the room, that is the centre's attendance failure. The wallet is already
     * debited at approval, and Module 3's open decision on wallet treatment for
     * an incomplete booking is deliberately not pre-empted here: the outcome is
     * recorded and Finance decides.
     */
    private void closeAtSlotEnd(CentreConsultation consultation) {
        LocalDateTime now = LocalDateTime.now();
        boolean centreJoined = attendanceRepository
                .existsByCentreConsultationIdAndParticipantRoleAndEventType(
                        consultation.getId(), ParticipantRole.CENTRE, AttendanceEventType.JOINED);

        consultation.setEndedAt(now);
        consultation.setOutcome(centreJoined ? Outcome.COMPLETED : Outcome.NO_SHOW);
        consultationRepository.save(consultation);

        closeRoom(consultation, "Slot end reached");

        CentreAppointment appointment = consultation.getCentreAppointment();
        if (appointment != null) {
            appointment.setStatus(centreJoined ? Status.COMPLETED : Status.NO_SHOW);
            if (!centreJoined) {
                appointment.setNoShowAt(now);
                appointment.setNoShowReason(
                        "The centre did not join before the session ended");
            }
            appointmentRepository.save(appointment);
        }
        log.info("Centre consultation {} closed at slot end, outcome {}",
                consultation.getPublicId(), consultation.getOutcome());
    }

    /** Deletes the room at the provider. An undeleted room is a way back in. */
    private void closeRoom(CentreConsultation consultation, String why) {
        if (consultation.getRoomName() == null || consultation.getRoomDeletedAt() != null) {
            return;
        }
        try {
            videoProvider.deleteRoom(consultation.getRoomName());
            consultation.setRoomDeletedAt(LocalDateTime.now());
            consultationRepository.save(consultation);
            recordAttendance(consultation, ParticipantRole.DOCTOR,
                    AttendanceEventType.ROOM_CLOSED, null, why);
        } catch (RuntimeException e) {
            // The session is over either way. A provider failure must not leave
            // the consultation open, but it must be visible: an undeleted room
            // with a live token is a clinical call anyone holding the link can
            // walk into.
            log.error("Could not delete centre room {} ({}). Room may still exist at provider.",
                    consultation.getRoomName(), why, e);
        }
    }

    // -----------------------------------------------------------------
    // Clinician control
    // -----------------------------------------------------------------

    @Transactional
    public CentreConsultation terminate(String consultationPublicId, TerminationReason reason,
                                        String note, String safetyAction) {
        CentreConsultation consultation = consultationRepository
                .findByPublicId(consultationPublicId)
                .orElseThrow(() -> new ConsultationService.ConsultationException(
                        "No such consultation"));

        if (consultation.getEndedAt() != null) {
            return consultation;
        }
        if (safetyAction == null || safetyAction.isBlank()) {
            throw new ConsultationService.ConsultationException(
                    "Record what was done to keep the patient safe before ending the session");
        }

        LocalDateTime now = LocalDateTime.now();
        consultation.setEndedAt(now);
        consultation.setTerminationReason(reason);
        consultation.setTerminationNote(note);
        consultation.setSafetyActionTaken(safetyAction);
        consultation.setOutcome(Outcome.TERMINATED_EARLY);
        consultation.setTerminatedBy(consultation.getDoctor());

        if (reason == TerminationReason.EMERGENCY) {
            // The patient is at the centre with staff present, so the
            // instruction is addressed to them rather than to a patient alone
            // at home.
            consultation.setEscalationInstruction(
                    "This service is not for emergencies. Centre staff should take the "
                            + "patient to the nearest emergency department, or call "
                            + configuration.getString(ConfigurationKeys.CLINICAL_EMERGENCY_NUMBER)
                            + ".");
        }
        consultationRepository.save(consultation);

        tokenRepository.revokeAllForCentreConsultation(consultation.getId(), now,
                "Session terminated: " + reason);

        closeRoom(consultation, "Terminated: " + reason);
        recordAttendance(consultation, ParticipantRole.DOCTOR,
                AttendanceEventType.TERMINATED, null, reason + ": " + note);

        CentreAppointment appointment = consultation.getCentreAppointment();
        if (appointment != null) {
            appointment.setStatus(Status.COMPLETED);
            appointmentRepository.save(appointment);
        }

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.CONSULTATION_TERMINATED)
                .entityType("CentreConsultation")
                .entityId(consultation.getId())
                .details(reason.name() + ". Safety action: " + safetyAction)
                .reason(note)
                .build());

        log.info("Centre consultation {} terminated: {}", consultation.getPublicId(), reason);
        return consultation;
    }

    /**
     * Video first, then audio, then approved backup phone.
     *
     * Module 3 permits a phone fallback that the FNPH pathway does not, for a
     * centre on a line too poor for audio over IP.
     */
    @Transactional
    public CentreConsultation switchModality(String consultationPublicId,
                                             Modality modality, String reason) {
        CentreConsultation consultation = consultationRepository
                .findByPublicId(consultationPublicId)
                .orElseThrow(() -> new ConsultationService.ConsultationException(
                        "No such consultation"));

        consultation.setModality(modality);
        consultation.setHasAudioFallback(
                modality == Modality.AUDIO || modality == Modality.PHONE_FALLBACK);
        consultationRepository.save(consultation);

        recordAttendance(consultation, ParticipantRole.DOCTOR,
                AttendanceEventType.MODALITY_CHANGED, modality.name(), reason);
        return consultation;
    }

    @Transactional
    public CentreConsultation confirmIdentity(String consultationPublicId) {
        CentreConsultation consultation = consultationRepository
                .findByPublicId(consultationPublicId)
                .orElseThrow(() -> new ConsultationService.ConsultationException(
                        "No such consultation"));

        consultation.setIdentityConfirmed(true);
        consultationRepository.save(consultation);
        recordAttendance(consultation, ParticipantRole.DOCTOR,
                AttendanceEventType.IDENTITY_CONFIRMED, null, null);
        return consultation;
    }
}