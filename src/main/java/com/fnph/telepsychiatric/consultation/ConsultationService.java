package com.fnph.telepsychiatric.consultation;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.appointment.AppointmentRepository;
import com.fnph.telepsychiatric.appointment.Status;
import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.configuration.ConfigurationKeys;
import com.fnph.telepsychiatric.configuration.ConfigurationService;
import com.fnph.telepsychiatric.consultation.video.DailyProperties;
import com.fnph.telepsychiatric.consultation.video.VideoProvider;
import com.fnph.telepsychiatric.notification.InAppNotificationService;
import com.fnph.telepsychiatric.notification.NotificationType;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.security.crypto.Tokens;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The video consultation.
 *
 * <h2>The server owns the clock</h2>
 *
 * The client shows a countdown; the server decides when the session is over.
 * A browser timer is a suggestion, and a clinician whose tab has been open
 * since this morning has a suggestion that is hours wrong.
 *
 * <h2>The slot end is fixed</h2>
 *
 * Remaining time counts down from the slot end, never from first join. A
 * patient arriving five minutes late gets twenty-five minutes. Extending one
 * session shortens the next, and the person who loses the time had nothing to
 * do with the delay.
 *
 * <h2>Three independent enforcements of the end</h2>
 *
 * The scheduled clock here, the room expiry at the provider, and the token
 * expiry. Any one surviving means an overrunning session still ends, including
 * when this application is down.
 *
 * <h2>Termination is the clinician's</h2>
 *
 * Failed identity, unacceptable privacy, persistent disruption, abuse, an
 * emergency, acute clinical unsuitability, unsafe connectivity. Each records a
 * reason and a safety action, and an emergency surfaces the approved
 * escalation instruction rather than leaving the clinician to remember it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConsultationService {

    private final ConsultationRepository consultationRepository;
    private final ParticipantTokenRepository tokenRepository;
    private final AttendanceEventRepository attendanceRepository;
    private final ConnectionQualityRepository qualityRepository;
    private final AppointmentRepository appointmentRepository;
    private final VideoProvider videoProvider;
    private final DailyProperties videoProperties;
    private final ConfigurationService configuration;
    private final InAppNotificationService notifications;
    private final AuditService auditService;

    // -----------------------------------------------------------------
    // Room lifecycle
    // -----------------------------------------------------------------

    /**
     * Creates the room, lazily, on the first join.
     *
     * Not at approval. A room created days ahead is a room sitting open at the
     * provider for days, and most of them would never be used because
     * appointments get cancelled.
     */
    @Transactional
    public Consultation ensureRoom(Appointment appointment) {
        return consultationRepository.findByAppointmentId(appointment.getId())
                .filter(c -> c.getRoomName() != null && c.getRoomDeletedAt() == null)
                .orElseGet(() -> createRoom(appointment));
    }

    private Consultation createRoom(Appointment appointment) {
        Consultation consultation = consultationRepository
                .findByAppointmentId(appointment.getId())
                .orElseGet(Consultation::new);

        consultation.setAppointment(appointment);
        consultation.setPatient(appointment.getPatient());
        consultation.setDoctor(appointment.getDoctor());
        consultation.setScheduledStartAt(appointment.getAppointmentDate());
        consultation.setScheduledEndAt(appointment.getScheduledEndAt());
        consultation.setModality(Modality.VIDEO);

        // A short grace past the slot end for clock skew, not for overrunning.
        LocalDateTime roomExpiry = appointment.getScheduledEndAt()
                .plusMinutes(videoProperties.getRoomGraceMinutes());

        // Off unless FNPH governance has approved consent, retention, access,
        // data location, deletion and incident response.
        boolean recording = configuration.getBoolean(ConfigurationKeys.RECORDING_ENABLED);

        String roomName = "fnph-" + appointment.getReference().toLowerCase();
        VideoProvider.VideoRoom room = videoProvider.createRoom(roomName, roomExpiry, recording);

        consultation.setRoomName(room.name());
        consultation.setRoomUrl(room.url());
        consultation.setRoomProviderId(room.providerId());
        consultation.setRoomExpiresAt(roomExpiry);
        consultation.setRoomCreatedAt(LocalDateTime.now());

        Consultation saved = consultationRepository.save(consultation);
        log.info("Video room {} created for appointment {}", room.name(), appointment.getReference());
        return saved;
    }

    // -----------------------------------------------------------------
    // Joining
    // -----------------------------------------------------------------

    /**
     * Issues a join token for one participant.
     *
     * The token is minted server-side and returned once. It is stored hashed,
     * because it is a bearer credential for a live clinical consultation and
     * there is no second factor inside a video room.
     *
     * @throws ConsultationException before the join window, after the no-show
     *         cutoff, or once the session has ended
     */
    @Transactional
    public JoinDetails join(String appointmentPublicId, ParticipantRole role, String ipAddress) {
        Appointment appointment = appointmentRepository.findByPublicId(appointmentPublicId)
                .orElseThrow(() -> new ConsultationException("No such appointment"));

        if (appointment.getStatus() != Status.APPROVED
                && appointment.getStatus() != Status.IN_PROGRESS) {
            throw new ConsultationException(
                    "That appointment is not confirmed, so there is nothing to join");
        }

        LocalDateTime now = LocalDateTime.now();
        int lead = configuration.getInt(ConfigurationKeys.ROOM_OPEN_LEAD_MINUTES);
        int cutoff = configuration.getInt(ConfigurationKeys.NO_SHOW_CUTOFF_MINUTES);

        LocalDateTime opensAt = appointment.getAppointmentDate().minusMinutes(lead);
        LocalDateTime patientCutoff = appointment.getAppointmentDate().plusMinutes(cutoff);

        if (now.isBefore(opensAt)) {
            throw new ConsultationException(
                    "The consultation room opens %d minutes before your appointment."
                            .formatted(lead));
        }
        if (now.isAfter(appointment.getScheduledEndAt())) {
            throw new ConsultationException("That consultation has ended");
        }
        // Only the patient is cut off. A clinician arriving late must still be
        // able to enter and record what happened, including the no-show.
        if (role == ParticipantRole.PATIENT && now.isAfter(patientCutoff)) {
            throw new ConsultationException(
                    "The join window closed %d minutes after the start time. Please contact "
                            .formatted(cutoff) + "the hospital to rebook.");
        }

        Consultation consultation = ensureRoom(appointment);

        String rawToken = videoProvider.createParticipantToken(
                consultation.getRoomName(),
                displayNameFor(appointment, role),
                // Only the clinician owns the call. A patient who could end it
                // could end it for the doctor.
                role == ParticipantRole.DOCTOR,
                opensAt,
                appointment.getScheduledEndAt().plusMinutes(videoProperties.getRoomGraceMinutes()));

        ParticipantToken token = new ParticipantToken();
        token.setConsultation(consultation);
        token.setParticipantRole(role);
        token.setTokenHash(Tokens.hash(rawToken));
        token.setDisplayName(displayNameFor(appointment, role));
        token.setNotBefore(opensAt);
        token.setExpiresAt(appointment.getScheduledEndAt()
                .plusMinutes(videoProperties.getRoomGraceMinutes()));
        token.setIssuedAt(now);
        token.setIssuedIp(ipAddress);
        if (role == ParticipantRole.DOCTOR) {
            token.setUser(appointment.getDoctor());
        } else {
            token.setPatient(appointment.getPatient());
        }
        tokenRepository.save(token);

        recordAttendance(consultation, role, AttendanceEventType.JOINED, null, null);

        if (role == ParticipantRole.DOCTOR && consultation.getDoctorJoinedAt() == null) {
            consultation.setDoctorJoinedAt(now);
            consultation.setStartedAt(now);
            appointment.setStatus(Status.IN_PROGRESS);
            appointmentRepository.save(appointment);
        }
        if (role == ParticipantRole.PATIENT && consultation.getPatientJoinedAt() == null) {
            consultation.setPatientJoinedAt(now);
        }
        consultationRepository.save(consultation);

        long remaining = Duration.between(now, appointment.getScheduledEndAt()).getSeconds();

        return new JoinDetails(
                consultation.getPublicId(),
                consultation.getRoomUrl(),
                rawToken,
                appointment.getAppointmentDate(),
                appointment.getScheduledEndAt(),
                Math.max(0, remaining),
                configuration.getInt(ConfigurationKeys.WARNING_ONE_MINUTES_REMAINING),
                configuration.getInt(ConfigurationKeys.WARNING_TWO_MINUTES_REMAINING),
                role == ParticipantRole.DOCTOR);
    }

    /**
     * The patient sees a room, never the doctor's name.
     *
     * The specification is explicit that the FNPH patient does not need the
     * doctor's name, and putting it on a video tile would work around that
     * without anyone deciding to.
     */
    private String displayNameFor(Appointment appointment, ParticipantRole role) {
        return switch (role) {
            case DOCTOR -> "Consultant";
            case PATIENT -> appointment.getPatient().getFirstName();
            case CENTRE -> "Centre";
        };
    }

    // -----------------------------------------------------------------
    // The clock
    // -----------------------------------------------------------------

    /**
     * Sends countdown warnings and ends sessions that have run to their slot
     * end. Run every 30 seconds.
     *
     * Two warnings, both configured, because the source documents disagree
     * about whether there is one at fifteen or one at ten. There are two.
     */
    @Transactional
    public int tick() {
        LocalDateTime now = LocalDateTime.now();
        int warnOne = configuration.getInt(ConfigurationKeys.WARNING_ONE_MINUTES_REMAINING);
        int warnTwo = configuration.getInt(ConfigurationKeys.WARNING_TWO_MINUTES_REMAINING);

        List<Consultation> running = consultationRepository.findRunning(now);
        int ended = 0;

        for (Consultation consultation : running) {
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

    private void closeAtSlotEnd(Consultation consultation) {
        LocalDateTime now = LocalDateTime.now();
        consultation.setEndedAt(now);
        consultation.setOutcome(consultation.getPatientJoinedAt() == null
                ? Outcome.NO_SHOW : Outcome.COMPLETED);
        consultationRepository.save(consultation);

        closeRoom(consultation, "Slot end reached");

        Appointment appointment = consultation.getAppointment();
        if (appointment != null) {
            appointment.setStatus(consultation.getPatientJoinedAt() == null
                    ? Status.NO_SHOW : Status.COMPLETED);
            if (consultation.getPatientJoinedAt() == null) {
                appointment.setNoShowAt(now);
                appointment.setNoShowReason("Patient did not join before the session ended");
            }
            appointmentRepository.save(appointment);
        }
        log.info("Consultation {} closed at slot end, outcome {}",
                consultation.getPublicId(), consultation.getOutcome());
    }

    // -----------------------------------------------------------------
    // Clinician control
    // -----------------------------------------------------------------

    /**
     * Ends a session early, with a reason and a safety action.
     *
     * An emergency returns the approved escalation instruction. A clinician
     * dealing with an emergency over a video call should not be reaching for a
     * policy document to find a number.
     */
    @Transactional
    public Consultation terminate(String consultationPublicId, TerminationReason reason,
                                  String note, String safetyAction) {
        Consultation consultation = consultationRepository.findByPublicId(consultationPublicId)
                .orElseThrow(() -> new ConsultationException("No such consultation"));

        if (consultation.getEndedAt() != null) {
            return consultation;
        }
        if (safetyAction == null || safetyAction.isBlank()) {
            throw new ConsultationException(
                    "Record what was done to keep the patient safe before ending the session");
        }

        LocalDateTime now = LocalDateTime.now();
        consultation.setEndedAt(now);
        consultation.setTerminationReason(reason);
        consultation.setTerminationNote(note);
        consultation.setSafetyActionTaken(safetyAction);
        consultation.setOutcome(Outcome.TERMINATED_EARLY);
        CurrentUser.get().ifPresent(principal ->
                consultation.setTerminatedBy(consultation.getDoctor()));

        if (reason == TerminationReason.EMERGENCY) {
            consultation.setEscalationInstruction(
                    "This service is not for emergencies. Direct the patient to the nearest "
                            + "emergency department, or call "
                            + configuration.getString(ConfigurationKeys.CLINICAL_EMERGENCY_NUMBER) + ".");
        }
        consultationRepository.save(consultation);

        // Kills every token. Without this, a participant ejected for abuse or a
        // privacy breach rejoins with the token they already hold.
        tokenRepository.revokeAllForConsultation(consultation.getId(), now,
                "Session terminated: " + reason);

        closeRoom(consultation, "Terminated: " + reason);
        recordAttendance(consultation, ParticipantRole.DOCTOR,
                AttendanceEventType.TERMINATED, null, reason + ": " + note);

        Appointment appointment = consultation.getAppointment();
        if (appointment != null) {
            appointment.setStatus(Status.COMPLETED);
            appointmentRepository.save(appointment);
        }

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.CONSULTATION_TERMINATED)
                .entityType("Consultation")
                .entityId(consultation.getId())
                .details(reason.name() + ". Safety action: " + safetyAction)
                .reason(note)
                .build());

        log.info("Consultation {} terminated: {}", consultation.getPublicId(), reason);
        return consultation;
    }

    /** Falls back from video to audio. Video is standard; audio is approved. */
    @Transactional
    public Consultation switchModality(String consultationPublicId, Modality modality, String reason) {
        Consultation consultation = consultationRepository.findByPublicId(consultationPublicId)
                .orElseThrow(() -> new ConsultationException("No such consultation"));

        consultation.setModality(modality);
        consultation.setHasAudioFallback(modality == Modality.AUDIO);
        consultationRepository.save(consultation);

        recordAttendance(consultation, ParticipantRole.DOCTOR,
                AttendanceEventType.MODALITY_CHANGED, modality.name(), reason);
        return consultation;
    }

    /** Records that the clinician confirmed who is on screen. */
    @Transactional
    public Consultation confirmIdentity(String consultationPublicId) {
        Consultation consultation = consultationRepository.findByPublicId(consultationPublicId)
                .orElseThrow(() -> new ConsultationException("No such consultation"));

        consultation.setIdentityConfirmed(true);
        consultationRepository.save(consultation);
        recordAttendance(consultation, ParticipantRole.DOCTOR,
                AttendanceEventType.IDENTITY_CONFIRMED, null, null);
        return consultation;
    }

    // -----------------------------------------------------------------

    /** Records a quality sample reported by a client. */
    @Transactional
    public void recordQuality(String consultationPublicId, ParticipantRole role,
                              Integer roundTripMs, java.math.BigDecimal packetLoss,
                              String videoQuality) {
        consultationRepository.findByPublicId(consultationPublicId).ifPresent(consultation -> {
            ConnectionQualityEvent event = new ConnectionQualityEvent();
            event.setConsultationId(consultation.getId());
            event.setParticipantRole(role);
            event.setRoundTripMs(roundTripMs);
            event.setPacketLossPercent(packetLoss);
            event.setVideoReceiveQuality(videoQuality);
            event.setRecordedAt(LocalDateTime.now());
            qualityRepository.save(event);

            if (packetLoss != null && packetLoss.doubleValue() > 5.0) {
                consultation.setConnectionIssues(consultation.getConnectionIssues() + 1);
                consultationRepository.save(consultation);
            }
        });
    }

    @Transactional(readOnly = true)
    public List<AttendanceEvent> attendanceFor(Long consultationId) {
        return attendanceRepository.findAllByConsultationIdOrderByOccurredAtAsc(consultationId);
    }

    /** Deletes rooms left open past their expiry. Run on a schedule. */
    @Transactional
    public int closeExpiredRooms() {
        List<Consultation> stale = consultationRepository.findRoomsToClose(LocalDateTime.now());
        stale.forEach(c -> closeRoom(c, "Room expiry reached"));
        return stale.size();
    }

    private void closeRoom(Consultation consultation, String reason) {
        if (consultation.getRoomName() == null || consultation.getRoomDeletedAt() != null) {
            return;
        }
        videoProvider.deleteRoom(consultation.getRoomName());
        consultation.setRoomDeletedAt(LocalDateTime.now());
        consultationRepository.save(consultation);
        recordAttendance(consultation, ParticipantRole.DOCTOR,
                AttendanceEventType.ROOM_CLOSED, null, reason);
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

    /** Everything a client needs to open the call. */
    public record JoinDetails(String consultationPublicId, String roomUrl, String token,
                              LocalDateTime scheduledStart, LocalDateTime scheduledEnd,
                              long remainingSeconds, int firstWarningMinutes,
                              int secondWarningMinutes, boolean isOwner) {
    }

    public static class ConsultationException extends RuntimeException {
        public ConsultationException(String message) {
            super(message);
        }
    }
}
