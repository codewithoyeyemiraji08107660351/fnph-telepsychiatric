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
 *
 * <h2>Where the clock lives</h2>
 *
 * The database half of the clock is {@link ConsultationClockSteps}. It is a
 * separate bean so its transactions are real: a @Transactional method called
 * from inside this class would bypass Spring's proxy and run with no
 * transaction at all.
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
    private final AuditService auditService;
    private final ConsultationClockSteps clockSteps;

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
        log.info("Video room {} created for appointment {}",
                room.name(), appointment.getReference());
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
                appointment.getScheduledEndAt()
                        .plusMinutes(videoProperties.getRoomGraceMinutes()));

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
     * Sends countdown warnings, ends sessions that have run to their slot end,
     * and sweeps rooms left open past their expiry. Run every 30 seconds.
     *
     * Two warnings, both configured, because the source documents disagree
     * about whether there is one at fifteen or one at ten. There are two.
     *
     * <h2>Not @Transactional, deliberately</h2>
     *
     * The database work happens in one short transaction inside
     * {@link ConsultationClockSteps}. Deleting a room at Daily happens here,
     * afterwards, holding no connection: its read timeout is twenty seconds,
     * and a dozen expired rooms inside a transaction would hold one connection
     * of twenty for four minutes. The symptom would be the whole application
     * stalling with a scheduled job as the cause and nothing in the request
     * logs pointing at it.
     *
     * This method also used to be one transaction for the whole pass with no
     * error handling around the provider call, so a single Daily failure threw
     * out of the loop and rolled back every counter and warning flag set before
     * it. The clock then made no progress for as long as the outage lasted:
     * nothing closed, and the same work was attempted and discarded on every
     * subsequent tick.
     *
     * @return how many consultations were closed
     */
    public int tick() {
        LocalDateTime now = LocalDateTime.now();
        int warnOne = configuration.getInt(ConfigurationKeys.WARNING_ONE_MINUTES_REMAINING);
        int warnTwo = configuration.getInt(ConfigurationKeys.WARNING_TWO_MINUTES_REMAINING);

        var result = clockSteps.advanceRunning(now, warnOne, warnTwo);

        // Rooms from consultations just closed, plus any left open from an
        // earlier pass where the provider was unreachable.
        deleteRooms(result.roomsToDelete());
        deleteRooms(clockSteps.expiredRooms(now));

        return result.ended();
    }

    /**
     * Deletes rooms left open past their expiry.
     *
     * tick() already does this, so this exists only for a separate schedule if
     * FNPH wants one. Running both against the same rows means two concurrent
     * deletes of one room, which is harmless but pointless; pick one.
     */
    public int closeExpiredRooms() {
        var rooms = clockSteps.expiredRooms(LocalDateTime.now());
        deleteRooms(rooms);
        return rooms.size();
    }

    /** The network half. Runs with no transaction open. */
    private void deleteRooms(List<ConsultationClockSteps.RoomRef> rooms) {
        for (var room : rooms) {
            try {
                videoProvider.deleteRoom(room.roomName());
                clockSteps.markRoomDeleted(room.consultationId(), room.why());
            } catch (RuntimeException e) {
                // One consultation's provider failure must not stop the sweep
                // for the others. This is the isolation the old single
                // transaction did not have: closeRoom had no try/catch at all,
                // so the first failure ended the pass.
                log.error("Could not delete room {} ({}). Room may still exist at "
                        + "the provider.", room.roomName(), room.why(), e);
            }
        }
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
                            + configuration.getString(ConfigurationKeys.CLINICAL_EMERGENCY_NUMBER)
                            + ".");
        }
        consultationRepository.save(consultation);

        // Kills every token, and before the room call. Without this, a
        // participant ejected for abuse or a privacy breach rejoins with the
        // token they already hold. Doing it first is also what makes it safe to
        // let this transaction stand if the provider is unreachable: the
        // credential is dead even though the room still exists.
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
    public Consultation switchModality(String consultationPublicId, Modality modality,
                                       String reason) {
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

    /**
     * Deletes the room at the provider. An undeleted room is a way back in.
     *
     * Used by {@link #terminate}, which is transactional, so this one call does
     * happen inside a transaction. It is a single deletion rather than a sweep,
     * and a clinician ending a session is already waiting on the response, so
     * the cost is one connection for one call rather than a pool held through a
     * batch.
     *
     * The try/catch is new. Without it a provider failure threw out of
     * terminate() and rolled the whole termination back, which left the session
     * open with its tokens un-revoked: a clinician ending a call for abuse
     * would see an error and the participant would still be in the room.
     */
    private void closeRoom(Consultation consultation, String reason) {
        if (consultation.getRoomName() == null || consultation.getRoomDeletedAt() != null) {
            return;
        }
        try {
            videoProvider.deleteRoom(consultation.getRoomName());
            consultation.setRoomDeletedAt(LocalDateTime.now());
            consultationRepository.save(consultation);
            recordAttendance(consultation, ParticipantRole.DOCTOR,
                    AttendanceEventType.ROOM_CLOSED, null, reason);
        } catch (RuntimeException e) {
            // Visible, not swallowed: an undeleted room with a live token is a
            // clinical call anyone holding the link can walk into. The tokens
            // are already revoked by the caller, and roomDeletedAt stays null,
            // so the next sweep retries the deletion.
            log.error("Could not delete room {} ({}). Room may still exist at provider.",
                    consultation.getRoomName(), reason, e);
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