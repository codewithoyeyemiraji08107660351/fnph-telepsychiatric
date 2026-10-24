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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Class-level {@code @Transactional(readOnly = true)} keeps one Hibernate
 * session open for the whole request. Without it, each repository call below
 * opened and closed its own session, and every lazy association accessed
 * afterwards (ReleaseBundle.appointment, ReleaseBundle.components,
 * Prescription.items, Investigation.items) threw
 * LazyInitializationException, surfaced to the client as a bare 500 on
 * every read of the release desk and individual bundles.
 *
 * release() and block() override the class default with a writable
 * @Transactional, since they call through to ReleaseService for the actual
 * mutation.
 */
@RestController
@RequestMapping("/api/v1/hub/releases")
@RequiredArgsConstructor
@Tag(name = "Clinical Bundle Release")
@Transactional(readOnly = true)
public class ReleaseController {

    private final ReleaseBundleRepository bundleRepository;
    private final ReleaseService releaseService;

    private final BundleContentAssembler contentAssembler;
    private final ProfessionalReviewRepository reviewRepository;
    private final ProfessionalReviewService reviewService;
    private final com.fnph.telepsychiatric.user.UserRepository userRepository;
    private final com.fnph.telepsychiatric.hub.HubTeamService teamService;
    private final com.fnph.telepsychiatric.appointment.CentreAppointmentRepository centreAppointmentRepository;

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
                    held.

                    Both pathways, one desk. A centre bundle carries `pathway: CENTRE`, the
                    centre's name, and the centre patient's local identifier in `ehrNumber`.
                    It used to be left off, so the only route to releasing one was the
                    notification, and a missed notification meant the centre never
                    received the care plan.

                    `openReviews` lists pharmacy and laboratory reviews still waiting, and
                    submitted ones that raised a query, with who holds each.

                    **Requires** `release_bundle.read`.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Unreleased bundles, oldest first."
    )
    public ResponseEntity<List<ReleaseDeskRow>> desk(
            @Parameter(
                    description = "Limit to one state. Omit for everything unreleased.",
                    example = "READY"
            )
            @RequestParam(required = false) BundleStatus status
    ) {
        List<BundleStatus> wanted = status == null
                ? List.of(
                BundleStatus.READY,
                BundleStatus.INCOMPLETE,
                BundleStatus.BLOCKED
        )
                : List.of(status);

        List<ReleaseDeskRow> rows = new java.util.ArrayList<>();

        for (ReleaseBundle bundle : bundleRepository.findFnphDesk(wanted)) {
            var appointment = bundle.getAppointment();
            var patient = appointment.getPatient();
            rows.add(new ReleaseDeskRow(
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
                    outstandingOf(bundle),
                    openReviewsOf(bundle),
                    "FNPH",
                    null));
        }

        for (ReleaseBundle bundle : bundleRepository.findCentreDesk(wanted)) {
            var appointment = bundle.getCentreAppointment();
            var patient = appointment.getCentrePatient();
            rows.add(new ReleaseDeskRow(
                    bundle.getPublicId(),
                    bundle.getStatus().name(),
                    bundle.getBlockedReason(),
                    bundle.getCreatedAt(),
                    appointment.getPublicId(),
                    appointment.getReference(),
                    appointment.getAppointmentDate(),
                    patient.getFirstName() + " " + patient.getLastName(),
                    patient.getCentrePatientId(),
                    appointment.getDoctor() == null ? null : appointment.getDoctor().getFullName(),
                    outstandingOf(bundle),
                    openReviewsOf(bundle),
                    "CENTRE",
                    appointment.getCentre().getName()));
        }

        rows.sort(java.util.Comparator.comparing(ReleaseDeskRow::createdAt,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        return ResponseEntity.ok(rows);
    }

    private List<String> outstandingOf(ReleaseBundle bundle) {
        return releaseService.componentsOf(bundle.getId()).stream()
                .filter(c -> !c.isSettled())
                .map(c -> c.getComponentType().name())
                .toList();
    }

    /**
     * Reviews still waiting, plus submitted ones that raised a query. The desk
     * shows who holds each so an unassigned or stalled review is visible where
     * the coordinator already looks.
     */
    private List<OpenReview> openReviewsOf(ReleaseBundle bundle) {
        return reviewRepository.findAllByBundleId(bundle.getId()).stream()
                .filter(r -> Boolean.TRUE.equals(r.getQueryRaised())
                        || (r.getSubmittedAt() == null && ProfessionalReviewService.isLive(r)))
                .map(r -> new OpenReview(
                        r.getPublicId(),
                        r.getReviewType().name(),
                        r.getPrescription() != null
                                ? r.getPrescription().getIssueNumber()
                                : r.getInvestigation() == null ? null : r.getInvestigation().getIssueNumber(),
                        r.getReviewer() == null ? null : r.getReviewer().getPublicId(),
                        r.getReviewer() == null ? null : r.getReviewer().getFullName(),
                        Boolean.TRUE.equals(r.getQueryRaised()) ? "QUERY_RAISED"
                                : r.getReviewer() == null ? "UNASSIGNED"
                                : r.getOpenedAt() == null ? "NOT_OPENED" : "IN_PROGRESS",
                        r.getAssignedAt(),
                        r.getOpenedAt()))
                .toList();
    }

    public record OpenReview(
            String reviewPublicId,
            String reviewType,
            String documentNumber,
            String reviewerPublicId,
            String reviewerName,
            @io.swagger.v3.oas.annotations.media.Schema(
                    allowableValues = {"UNASSIGNED", "NOT_OPENED", "IN_PROGRESS", "QUERY_RAISED"})
            String state,
            java.time.LocalDateTime assignedAt,
            java.time.LocalDateTime openedAt
    ) {
    }

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
            List<String> outstanding,
            List<OpenReview> openReviews,
            @io.swagger.v3.oas.annotations.media.Schema(allowableValues = {"FNPH", "CENTRE"})
            String pathway,
            @io.swagger.v3.oas.annotations.media.Schema(nullable = true)
            String centreName
    ) {
    }

    @GetMapping("/{bundlePublicId}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).RELEASE_BUNDLE_READ)")
    @Operation(
            summary = "Bundle completeness and clinical contents",
            description = """
                    Returns every expected component, its completion state, and the
                    read-only clinical material produced by the consultation.

                    The Hub Coordinator can inspect the prescription, investigation,
                    clinical note and follow-up recommendation before releasing the
                    complete bundle.

                    This is an administrative read. Clinical documents cannot be edited
                    through this endpoint.

                    A component marked `notRequired` is not holding anything: the doctor
                    decided none was needed and said why.

                    **Requires** `release_bundle.read`.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "Bundle and its read-only clinical contents returned.",
            content = @Content(
                    schema = @Schema(
                            implementation = ReleaseBundleResponse.class
                    )
            )
    )
    public ResponseEntity<ReleaseBundleResponse> get(
            @PathVariable String bundlePublicId
    ) {
        ReleaseBundle bundle = bundleRepository
                .findByPublicId(bundlePublicId)
                .orElseThrow(() ->
                        new jakarta.persistence.EntityNotFoundException(
                                "No such bundle"
                        )
                );

        return ResponseEntity.ok(toResponse(bundle));
    }

    @PostMapping("/{bundlePublicId}/release")
    @Transactional
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).RELEASE_BUNDLE_RELEASE)")
    @Operation(
            summary = "Release the complete bundle to the patient",
            description = """
                    Releases everything at once and notifies the patient that their
                    documents are ready.

                    **All or nothing.** There is no endpoint that releases one component.
                    A patient given a prescription while their investigation request is
                    still under review acts on half their care plan and has no way to know
                    that is what happened.

                    Refused unless every component is settled, and the error names what is
                    outstanding so you know who to chase.

                    **This is an administrative completeness check, not a clinical one.**
                    You confirm the paperwork is done. You do not judge the clinical
                    content, and nothing here lets you change it.

                    **Requires** `release_bundle.release`, held by the Hub Coordinator.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Released. The patient has been told.",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ReleaseBundleResponse.class
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Not ready. The message names what is outstanding.",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class
                            )
                    )
            )
    })
    public ResponseEntity<ReleaseBundleResponse> release(
            @PathVariable String bundlePublicId,
            @Parameter(
                    description = "Optional note recorded with the release."
            )
            @RequestParam(required = false) String notes
    ) {
        return ResponseEntity.ok(
                toResponse(
                        releaseService.release(bundlePublicId, notes)
                )
        );
    }

    @PostMapping("/{bundlePublicId}/block")
    @Transactional
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
                    """
    )
    @ApiResponse(responseCode = "200", description = "Held.")
    public ResponseEntity<ReleaseBundleResponse> block(
            @PathVariable String bundlePublicId,
            @Parameter(
                    description = "Why it is being held.",
                    required = true
            )
            @RequestParam String reason
    ) {
        return ResponseEntity.ok(
                toResponse(
                        releaseService.block(bundlePublicId, reason)
                )
        );
    }

    @PostMapping("/{bundlePublicId}/reviewers")
    @Transactional
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).APPOINTMENT_ASSIGN_TEAM)")
    @Operation(
            summary = "Assign the pharmacist or laboratory technician on a centre bundle",
            description = """
                    Moves every open review of that kind on the bundle to the named person,
                    and records them on the centre appointment.

                    Centre approval may leave the pharmacist or technician empty. A review
                    issued then sat in nobody's queue, the hub was never told, and the
                    FNPH team tools could not reach a centre appointment, so the bundle
                    could never become ready. This is the way out.

                    FNPH bundles are refused: change their team from the consultation's
                    team panel, which also keeps the appointment's team in step.

                    The person must be an active hospital account holding the role.

                    **Requires** `appointment.assign_team`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Assigned. The bundle is returned.",
                    content = @Content(schema = @Schema(implementation = ReleaseBundleResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Not eligible: inactive, centre staff, or lacks the role.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409",
                    description = "An FNPH bundle, or one already released.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ReleaseBundleResponse> assignCentreReviewer(
            @PathVariable String bundlePublicId,
            @Parameter(description = "Which review.", example = "PHARMACY", required = true)
            @RequestParam ReviewType type,
            @Parameter(required = true) @RequestParam String userPublicId,
            @Parameter(description = "Why. Goes to the audit trail.", required = true)
            @RequestParam String reason) {

        ReleaseBundle bundle = bundleRepository.findByPublicId(bundlePublicId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("No such bundle"));
        var appointment = bundle.getCentreAppointment();
        if (appointment == null) {
            throw new ClinicalService.ClinicalException(
                    "This is an FNPH bundle. Change its team from the consultation's team panel.");
        }
        if (bundle.getStatus() == BundleStatus.RELEASED) {
            throw new ClinicalService.ClinicalException("This bundle is already released.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Say why, for the audit trail.");
        }

        var user = userRepository.findByPublicId(userPublicId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("No such user"));
        teamService.assertEligible(user, type == ReviewType.PHARMACY
                ? com.fnph.telepsychiatric.hub.HubTeamService.Role.PHARMACIST
                : com.fnph.telepsychiatric.hub.HubTeamService.Role.LABORATORY);

        if (type == ReviewType.PHARMACY) {
            appointment.setPharmacy(user);
        } else {
            appointment.setLaboratory(user);
        }
        centreAppointmentRepository.save(appointment);
        reviewService.reassignOpen(bundle.getId(), type, user, reason.trim());

        return ResponseEntity.ok(toResponse(bundle));
    }

    private ReleaseBundleResponse toResponse(ReleaseBundle bundle) {

        var components = releaseService.componentsOf(bundle.getId())
                .stream()
                .map(c -> new ReleaseBundleResponse.ComponentState(
                        c.getComponentType().name(),
                        Boolean.TRUE.equals(c.getIsComplete()),
                        Boolean.TRUE.equals(c.getNotRequired()),
                        c.getNotRequiredReason(),
                        !c.isSettled()
                ))
                .toList();

        ReleaseBundleResponse.ClinicalContent clinicalContent =
                contentAssembler.contentOf(bundle);

        var centreAppointment = bundle.getCentreAppointment();

        return new ReleaseBundleResponse(
                bundle.getPublicId(),
                bundle.getStatus().name(),
                bundle.getBlockedReason(),
                components,
                clinicalContent,
                bundle.getReleasedBy(),
                bundle.getReleasedAt(),
                bundle.getAppointment() == null
                        ? null
                        : bundle.getAppointment().getPublicId(),
                centreAppointment == null ? "FNPH" : "CENTRE",
                centreAppointment == null ? null : centreAppointment.getCentre().getName(),
                centreAppointment == null ? null : centreAppointment.getPublicId(),
                centreAppointment == null ? null : centreAppointment.getReference(),
                openReviewsOf(bundle)
        );
    }
}
