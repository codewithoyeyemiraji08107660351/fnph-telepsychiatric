package com.fnph.telepsychiatric.consultation.api;

import com.fnph.telepsychiatric.consultation.CentreConsultationService;
import com.fnph.telepsychiatric.consultation.ConsultationService;
import com.fnph.telepsychiatric.consultation.ParticipantRole;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/centre-consultations")
@RequiredArgsConstructor
@Tag(name = "Centre consultations")
public class CentreConsultationController {

    private final CentreConsultationService centreConsultationService;

    @PostMapping("/{centreAppointmentPublicId}/join/centre")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CONSULTATION_JOIN_AS_CENTRE)")
    @Operation(
            summary = "Join as the referring centre",
            description = """
                    Returns the room and a join token for the centre.

                    **The patient is with you, not on the call.** They attend at the
                    centre, which is why there is no patient join on this pathway.

                    **You are a participant, not the owner.** The FNPH clinician admits,
                    mutes and ends the session.

                    **The join window closes after the configured cutoff**, because the
                    centre is responsible for having the patient in the room.

                    **Requires** `consultation.join_as_centre`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Room URL and token returned.",
                    content = @Content(schema = @Schema(implementation = JoinConsultationResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Before the join window, after the cutoff or the session end, "
                            + "or the appointment is not confirmed.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Not this centre's appointment.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<JoinConsultationResponse> joinAsCentre(
            @PathVariable String centreAppointmentPublicId, HttpServletRequest http) {
        return ResponseEntity.ok(toResponse(centreConsultationService.join(
                centreAppointmentPublicId, ParticipantRole.CENTRE, clientIp(http))));
    }

    @PostMapping("/{centreAppointmentPublicId}/join/doctor")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CONSULTATION_JOIN_AS_DOCTOR)")
    @Operation(
            summary = "Join a centre consultation as the clinician",
            description = """
                    Creates the room on first join and returns an owner token.

                    Joining marks the centre appointment IN_PROGRESS and starts the clock.

                    **A clinician is not subject to the cutoff.** Arriving late they must
                    still be able to enter and record what happened, including that the
                    centre did not bring the patient.

                    **Requires** `consultation.join_as_doctor`.
                    """)
    @ApiResponse(responseCode = "200", description = "Room URL and owner token returned.",
            content = @Content(schema = @Schema(implementation = JoinConsultationResponse.class)))
    public ResponseEntity<JoinConsultationResponse> joinAsDoctor(
            @PathVariable String centreAppointmentPublicId, HttpServletRequest http) {
        return ResponseEntity.ok(toResponse(centreConsultationService.join(
                centreAppointmentPublicId, ParticipantRole.DOCTOR, clientIp(http))));
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