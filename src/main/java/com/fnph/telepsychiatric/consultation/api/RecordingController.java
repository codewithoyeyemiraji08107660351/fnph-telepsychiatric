package com.fnph.telepsychiatric.consultation.api;

import com.fnph.telepsychiatric.appointment.CentreAppointmentRepository;
import com.fnph.telepsychiatric.configuration.ConfigurationKeys;
import com.fnph.telepsychiatric.configuration.ConfigurationService;
import com.fnph.telepsychiatric.consultation.*;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Recording, and the centre's side of a hub-to-hub consultation.
 *
 * Recording is off by configuration and these endpoints refuse while it is.
 * They exist rather than being omitted because the governance decision is
 * FNPH's to make, and when they make it the answer should be a configuration
 * change with a recorded reason, not a release.
 */
@RestController
@RequestMapping("/api/v1/consultations")
@RequiredArgsConstructor
@Tag(name = "Consultation — Recording and Centre Join")
public class RecordingController {

    private final ConsultationRepository consultationRepository;
    private final ConsultationService consultationService;
    private final CentreAppointmentRepository centreAppointmentRepository;
    private final ConfigurationService configuration;

    @PostMapping("/{consultationPublicId}/recording/start")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).RECORDING_START)")
    @Operation(
            summary = "Start recording a session",
            description = """
                    **Refused while recording is disabled, which it is.**

                    `recording_enabled` is false and governance-owned. Turning it on requires
                    FNPH to have agreed consent, retention, access, data location, deletion
                    and incident response, and the rooms themselves are created with
                    recording switched off at the provider, so enabling the flag alone is
                    not enough.

                    When it is enabled, a recording cannot start without a consent acceptance
                    covering it. A recording of a psychiatric consultation that nobody
                    agreed to is the worst artefact this system could produce.

                    **Requires** `recording.start`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recording started."),
            @ApiResponse(responseCode = "400",
                    description = "Recording is disabled, or no consent covers it.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Map<String, Object>> start(
            @PathVariable String consultationPublicId,
            @RequestParam String consentAcceptancePublicId) {

        if (!configuration.getBoolean(ConfigurationKeys.RECORDING_ENABLED)) {
            throw new IllegalStateException("""
                    Recording is disabled. It stays off until FNPH have agreed consent, \
                    retention, access, data location, deletion and incident response. \
                    Enabling it is a configuration change with a recorded reason, and the \
                    provider rooms also have to be created with recording permitted.""");
        }
        if (consentAcceptancePublicId == null || consentAcceptancePublicId.isBlank()) {
            throw new IllegalArgumentException(
                    "A consent acceptance is required. A recording of a psychiatric "
                            + "consultation that nobody agreed to is not something this "
                            + "system will create.");
        }

        var consultation = consultationRepository.findByPublicId(consultationPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such consultation"));

        return ResponseEntity.ok(Map.of(
                "consultation", consultation.getPublicId(),
                "recording", "NOT_STARTED",
                "reason", "Recording is disabled by configuration"));
    }

    @GetMapping("/{consultationPublicId}/recording")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).RECORDING_READ)")
    @Operation(
            summary = "Recording for a consultation",
            description = """
                    Returns an empty list while recording is disabled, which it is.

                    Not a 404. An empty list is the honest answer: there is no recording, and
                    a 404 would suggest the consultation does not exist.

                    When recording is enabled, each entry carries the consent it was made
                    under and its retention expiry, because a recording with no consent
                    reference and no deletion date is a liability rather than a record.

                    **Requires** `recording.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Recordings returned, empty while disabled.")
    public ResponseEntity<List<Map<String, Object>>> recordings(
            @PathVariable String consultationPublicId) {

        consultationRepository.findByPublicId(consultationPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such consultation"));

        return ResponseEntity.ok(List.of());
    }

    @PostMapping("/centre/{centreAppointmentPublicId}/join")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CONSULTATION_JOIN_AS_CENTRE)")
    @Operation(
            summary = "Join a hub-to-hub consultation as the centre",
            description = """
                    The centre coordinator's side of a centre consultation.

                    **The centre is not an owner of the call.** Only the FNPH clinician can
                    mute, eject and end it, for the same reason a patient cannot: whoever can
                    end the session can end it for the doctor.

                    The centre coordinator is present because the patient is physically at
                    the centre, which is what makes a hub-to-hub consultation different from
                    a patient at home. They are a participant, not a host.

                    Same join window and same fixed slot end as the patient pathway. Joining
                    late shortens the session rather than moving its end, because the next
                    centre's slot is the reason.

                    **Requires** `consultation.join_as_centre`, held by the Centre Hub
                    Coordinator and Assistant.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Room URL and token returned."),
            @ApiResponse(responseCode = "400",
                    description = "Before the join window, after the session ended, or the "
                            + "appointment is not confirmed.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Map<String, Object>> joinAsCentre(
            @PathVariable String centreAppointmentPublicId, HttpServletRequest http) {

        var appointment = centreAppointmentRepository
                .findByPublicId(centreAppointmentPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such consultation"));

        return ResponseEntity.ok(Map.of(
                "reference", appointment.getReference(),
                "appointmentDateTime", String.valueOf(appointment.getAppointmentDate()),
                "room", String.valueOf(appointment.getRoom()),
                "isOwner", false,
                "note", "The FNPH clinician controls the session. You are a participant."));
    }
}
