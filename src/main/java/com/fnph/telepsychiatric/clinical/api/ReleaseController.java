package com.fnph.telepsychiatric.clinical.api;

import com.fnph.telepsychiatric.clinical.*;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/hub/releases")
@RequiredArgsConstructor
@Tag(name = "Clinical Bundle Release")
public class ReleaseController {

    private final ReleaseBundleRepository bundleRepository;
    private final ReleaseService releaseService;

    @GetMapping
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).RELEASE_BUNDLE_READ)")
    @Operation(
            summary = "Release desk",
            description = """
                    Hospital bundles not yet released, oldest first, with who each is for
                    and what is holding it.

                    **This is the Hub Coordinator's release desk, and it did not exist.**
                    The single-bundle endpoint needs a public identifier the coordinator had
                    no way to obtain, so completed prescriptions and investigations sat
                    unreleased with nobody able to find them.

                    `READY` bundles can be released now. `INCOMPLETE` ones name the
                    components still outstanding. `BLOCKED` ones carry the reason they were
                    held. Centre bundles are not listed; they belong to the centre pathway.

                    **Requires** `release_bundle.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Unreleased bundles, oldest first.")
    public ResponseEntity<List<ReleaseDeskRow>> desk(
            @Parameter(description = "Limit to one state. Omit for everything unreleased.",
                    example = "READY")
            @RequestParam(required = false) BundleStatus status) {
        List<BundleStatus> wanted = status == null
                ? List.of(BundleStatus.READY, BundleStatus.INCOMPLETE, BundleStatus.BLOCKED)
                : List.of(status);
        return ResponseEntity.ok(bundleRepository.findFnphDesk(wanted).stream().map(bundle -> {
            var appointment = bundle.getAppointment();
            var patient = appointment.getPatient();
            List<String> outstanding = releaseService.componentsOf(bundle.getId()).stream()
                    .filter(c -> !c.isSettled())
                    .map(c -> c.getComponentType().name())
                    .toList();
            return new ReleaseDeskRow(
                    bundle.getPublicId(),
                    bundle.getStatus().name(),
                    bundle.getBlockedReason(),
                    bundle.getCreatedAt(),
                    appointment.getPublicId(),
                    appointment.getReference(),
                    appointment.getAppointmentDate(),
                    patient.getFirstName() + " " + patient.getLastName(),
                    patient.getEhrNumber(),
                    appointment.getDoctor() == null ? null : appointment.getDoctor().getFullName(),
                    outstanding);
        }).toList());
    }

    /** One line on the release desk. Identifies the patient; carries no clinical content. */
    public record ReleaseDeskRow(
            String publicId,
            String status,
            String blockedReason,
            java.time.LocalDateTime createdAt,
            String appointmentPublicId,
            String appointmentReference,
            java.time.LocalDateTime appointmentDate,
            String patientName,
            String ehrNumber,
            String doctorName,
            List<String> outstanding) {
    }

    @GetMapping("/{bundlePublicId}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).RELEASE_BUNDLE_READ)")
    @Operation(
            summary = "Bundle completeness",
            description = """
                    Every expected component and where it stands.

                    Read `outstanding` to see what is holding the release. A component
                    marked `notRequired` is not holding anything: the doctor decided none
                    was needed and said why.

                    That distinction matters. Without it, a consultation that legitimately
                    produced no investigation request would sit blocked forever waiting for
                    a document nobody intends to write.

                    **Requires** `release_bundle.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Bundle returned.",
            content = @Content(schema = @Schema(implementation = ReleaseBundleResponse.class)))
    public ResponseEntity<ReleaseBundleResponse> get(@PathVariable String bundlePublicId) {
        ReleaseBundle bundle = bundleRepository.findByPublicId(bundlePublicId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("No such bundle"));
        return ResponseEntity.ok(toResponse(bundle));
    }

    @PostMapping("/{bundlePublicId}/release")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).RELEASE_BUNDLE_RELEASE)")
    @Operation(
            summary = "Release the complete bundle to the patient",
            description = """
                    Releases everything at once and notifies the patient that their
                    documents are ready.

                    **All or nothing.** There is no endpoint that releases one component. A
                    patient given a prescription while their investigation request is still
                    under review acts on half their care plan and has no way to know that is
                    what happened.

                    Refused unless every component is settled, and the error names what is
                    outstanding so you know who to chase.

                    **This is an administrative completeness check, not a clinical one.**
                    You confirm the paperwork is done. You do not judge the clinical
                    content, and nothing here lets you change it.

                    **Requires** `release_bundle.release`, held by the Hub Coordinator.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Released. The patient has been told.",
                    content = @Content(schema = @Schema(implementation = ReleaseBundleResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Not ready. The message names what is outstanding.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ReleaseBundleResponse> release(
            @PathVariable String bundlePublicId,
            @Parameter(description = "Optional note recorded with the release.")
            @RequestParam(required = false) String notes) {
        return ResponseEntity.ok(toResponse(releaseService.release(bundlePublicId, notes)));
    }

    @PostMapping("/{bundlePublicId}/block")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).RELEASE_BUNDLE_RELEASE)")
    @Operation(
            summary = "Hold a bundle",
            description = """
                    Holds a bundle deliberately with a stated reason, for example while a
                    concern raised in review is taken to the multidisciplinary team.

                    An already-released bundle cannot be blocked. Withdrawing something the
                    patient already has is not possible; issue a superseding document
                    instead.

                    **Requires** `release_bundle.release`.
                    """)
    @ApiResponse(responseCode = "200", description = "Held.")
    public ResponseEntity<ReleaseBundleResponse> block(
            @PathVariable String bundlePublicId,
            @Parameter(description = "Why it is being held.", required = true)
            @RequestParam String reason) {
        return ResponseEntity.ok(toResponse(releaseService.block(bundlePublicId, reason)));
    }

    private ReleaseBundleResponse toResponse(ReleaseBundle bundle) {
        var components = releaseService.componentsOf(bundle.getId()).stream()
                .map(c -> new ReleaseBundleResponse.ComponentState(
                        c.getComponentType().name(),
                        Boolean.TRUE.equals(c.getIsComplete()),
                        Boolean.TRUE.equals(c.getNotRequired()),
                        c.getNotRequiredReason(),
                        !c.isSettled()))
                .toList();

        return new ReleaseBundleResponse(
                bundle.getPublicId(), bundle.getStatus().name(), bundle.getBlockedReason(),
                components, bundle.getReleasedBy(), bundle.getReleasedAt());
    }
}
