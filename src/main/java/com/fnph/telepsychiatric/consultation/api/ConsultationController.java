package com.fnph.telepsychiatric.consultation.api;

import com.fnph.telepsychiatric.consultation.*;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/consultations")
@RequiredArgsConstructor
@Tag(name = "Consultations")
public class ConsultationController {

    private final ConsultationService consultationService;

    @PostMapping("/{appointmentPublicId}/join/doctor")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CONSULTATION_JOIN_AS_DOCTOR)")
    @Operation(
            summary = "Join as the consulting clinician",
            description = """
                    Creates the room if it does not exist yet and returns a join token.

                    **The room is created on first join, not at approval.** A room created
                    days ahead sits open at the provider for days, and most would never be
                    used because appointments get cancelled.

                    The clinician joins as owner: they can mute, eject and end the call.
                    The patient cannot, because a patient who could end the session could
                    end it for the doctor.

                    Joining marks the appointment IN_PROGRESS and starts the session clock.

                    **A clinician is not subject to the no-show cutoff.** Arriving late,
                    they must still be able to enter and record what happened, including
                    that the patient did not attend.

                    **Requires** `consultation.join_as_doctor`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Room URL and token returned.",
                    content = @Content(schema = @Schema(implementation = JoinConsultationResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Before the join window, after the session ended, or the "
                            + "appointment is not confirmed.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503",
                    description = "The video provider is unavailable. Try again shortly.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<JoinConsultationResponse> joinAsDoctor(
            @PathVariable String appointmentPublicId, HttpServletRequest http) {
        return ResponseEntity.ok(toResponse(consultationService.join(
                appointmentPublicId, ParticipantRole.DOCTOR, clientIp(http))));
    }

    @PostMapping("/{appointmentPublicId}/join/patient")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CONSULTATION_JOIN_AS_PATIENT)")
    @Operation(
            summary = "Join my consultation",
            description = """
                    Returns the room and a join token for the patient.

                    **The room opens shortly before the appointment**, not earlier. Trying
                    sooner returns 400 with how long to wait.

                    **The join window closes after the configured cutoff.** After that the
                    link is dead and the appointment is recorded as a no-show; the patient
                    is told to contact the hospital to rebook.

                    **Joining late does not extend the session.** `remainingSeconds` counts
                    down from the fixed slot end, because extending this session would
                    shorten the next patient's.

                    The patient sees the clinician as "Consultant". The doctor's name is
                    deliberately not disclosed on the FNPH pathway.

                    **Requires** `consultation.join_as_patient`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Room URL and token returned.",
                    content = @Content(schema = @Schema(implementation = JoinConsultationResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Too early, or the join window has closed.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<JoinConsultationResponse> joinAsPatient(
            @PathVariable String appointmentPublicId, HttpServletRequest http) {
        return ResponseEntity.ok(toResponse(consultationService.join(
                appointmentPublicId, ParticipantRole.PATIENT, clientIp(http))));
    }

    @PostMapping("/{consultationPublicId}/identity-confirmed")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CONSULTATION_JOIN_AS_DOCTOR)")
    @Operation(
            summary = "Confirm the person on screen is the patient on the record",
            description = """
                    Records the identity check.

                    Worth doing explicitly because failed identity verification is one of
                    the stated grounds for ending a session, and a consultation that
                    proceeded without the check is one nobody can later say was with the
                    right person.

                    **Requires** `consultation.join_as_doctor`.
                    """)
    @ApiResponse(responseCode = "204", description = "Recorded.")
    public ResponseEntity<Void> confirmIdentity(@PathVariable String consultationPublicId) {
        consultationService.confirmIdentity(consultationPublicId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{consultationPublicId}/modality")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CONSULTATION_SWITCH_MODALITY)")
    @Operation(
            summary = "Fall back from video to audio",
            description = """
                    Video is the standard; audio is the approved fallback for a poor line.

                    Recorded rather than left to the participants, so a session conducted
                    on audio is visibly a session conducted on audio, and a later question
                    about whether the clinician could see the patient has an answer.

                    **Requires** `consultation.switch_modality`.
                    """)
    @ApiResponse(responseCode = "204", description = "Modality changed and recorded.")
    public ResponseEntity<Void> switchModality(
            @PathVariable String consultationPublicId,
            @Parameter(description = "New modality.", example = "AUDIO", required = true)
            @RequestParam Modality modality,
            @Parameter(description = "Why.", example = "Video unstable at the patient's end")
            @RequestParam(required = false) String reason) {
        consultationService.switchModality(consultationPublicId, modality, reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{consultationPublicId}/terminate")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CONSULTATION_TERMINATE)")
    @Operation(
            summary = "End the session early",
            description = """
                    Ends the call, revokes every participant token and deletes the room.

                    **Revoking the tokens matters.** Without it, someone ejected for abuse
                    or a privacy breach rejoins with the token they already hold.

                    **A safety action is mandatory** and the request is refused without
                    one. A session ended for abuse, an emergency or acute clinical
                    unsuitability leaves a patient somewhere, and what happened next is the
                    part that matters clinically and the part a later review will ask about.

                    `EMERGENCY` returns the approved escalation instruction, so a clinician
                    handling one is not looking up a number in a policy document.

                    **Requires** `consultation.terminate`, held by the doctor.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Session ended and recorded."),
            @ApiResponse(responseCode = "400", description = "No safety action given.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> terminate(@PathVariable String consultationPublicId,
                                          @Valid @RequestBody TerminateConsultationRequest request) {
        consultationService.terminate(consultationPublicId,
                TerminationReason.valueOf(request.reason()), request.note(), request.safetyAction());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{consultationPublicId}/quality")
    @Operation(
            summary = "Report connection quality",
            description = """
                    Post a sample every 30 seconds or so from both ends.

                    "The call kept dropping" is the most common complaint about
                    telemedicine anywhere, and without numbers it is one person's word
                    against another's about whether the service or the line was at fault. A
                    clinician ending a session for unsafe connectivity also needs something
                    to point at.

                    **Requires** any authenticated session.
                    """)
    @ApiResponse(responseCode = "204", description = "Recorded.")
    public ResponseEntity<Void> reportQuality(
            @PathVariable String consultationPublicId,
            @RequestParam ParticipantRole role,
            @RequestParam(required = false) Integer roundTripMs,
            @RequestParam(required = false) BigDecimal packetLossPercent,
            @RequestParam(required = false) String videoQuality) {
        consultationService.recordQuality(consultationPublicId, role,
                roundTripMs, packetLossPercent, videoQuality);
        return ResponseEntity.noContent().build();
    }

    private JoinConsultationResponse toResponse(ConsultationService.JoinDetails d) {
        return new JoinConsultationResponse(
                d.consultationPublicId(), d.roomUrl(), d.token(),
                d.scheduledStart(), d.scheduledEnd(), d.remainingSeconds(),
                d.firstWarningMinutes(), d.secondWarningMinutes(), d.isOwner());
    }

    private String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        return (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim() : http.getRemoteAddr();
    }
}
