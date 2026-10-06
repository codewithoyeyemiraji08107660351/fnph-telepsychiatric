package com.fnph.telepsychiatric.clinical.api;

import com.fnph.telepsychiatric.appointment.AppointmentRepository;
import com.fnph.telepsychiatric.clinical.ClinicalEditRevision;
import com.fnph.telepsychiatric.clinical.FollowUp;
import com.fnph.telepsychiatric.clinical.FollowUpStatus;
import com.fnph.telepsychiatric.clinical.HubClinicalEditService;
import com.fnph.telepsychiatric.clinical.ProfessionalReview;
import com.fnph.telepsychiatric.clinical.ProfessionalReviewRepository;
import com.fnph.telepsychiatric.clinical.ReleaseBundleRepository;
import com.fnph.telepsychiatric.consultation.Modality;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Hub Coordinator oversight of one consultation's multidisciplinary work.
 *
 * Reads are guarded by {@code release_bundle.read}. Edits need
 * {@code hub.clinical_edit}, a reason, and the {@code updatedAt} the editor
 * loaded, so a later change by someone else is never silently overwritten.
 * Class-level transactions are read-only; each edit method declares its own.
 */
@RestController
@RequestMapping("/api/v1/hub/appointments/{appointmentPublicId}")
@RequiredArgsConstructor
@Tag(name = "Hub Oversight")
@Transactional(readOnly = true)
public class HubOversightController {

    private final AppointmentRepository appointmentRepository;
    private final ReleaseBundleRepository bundleRepository;
    private final ProfessionalReviewRepository reviewRepository;
    private final HubClinicalEditService editService;

    @GetMapping("/reviews")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).RELEASE_BUNDLE_READ)")
    @Operation(
            summary = "Pharmacy and laboratory reviews for one consultation",
            description = """
                    Every review attached to this consultation's prescriptions and
                    investigations, including verified ones with notes, not only raised
                    queries. Oldest assignment first.

                    Empty when the consultation has no release bundle yet.

                    Guarded by `release_bundle.read`, not `review.read`: pharmacists and
                    laboratory technicians hold `review.read`, and must not see every
                    review in the hospital.
                    """)
    @ApiResponse(responseCode = "200", description = "Reviews returned.")
    public ResponseEntity<List<HubReviewRow>> reviews(@PathVariable String appointmentPublicId) {
        var appointment = appointmentRepository.findByPublicId(appointmentPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such appointment"));

        List<HubReviewRow> rows = bundleRepository.findByAppointmentId(appointment.getId())
                .map(bundle -> reviewRepository.findAllByBundleId(bundle.getId())
                        .stream()
                        .map(HubOversightController::row)
                        .toList())
                .orElse(List.of());

        return ResponseEntity.ok(rows);
    }

    @PutMapping("/reviews/{reviewPublicId}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).HUB_CLINICAL_EDIT)")
    @Transactional
    @Operation(
            summary = "Edit a submitted review's notes",
            description = """
                    Changes the reviewer's notes, and the query text when a query was
                    raised. The outcome stays the reviewer's decision.

                    Send the review's current `updatedAt` as `expectedUpdatedAt`. If the
                    review changed since then, the edit is refused with 409.

                    The previous text is kept in the edit history and the reviewer is
                    notified with the reason.

                    **Requires** `hub.clinical_edit`.
                    """)
    @ApiResponse(responseCode = "200", description = "Edited.")
    public ResponseEntity<HubReviewRow> editReview(
            @PathVariable String appointmentPublicId,
            @PathVariable String reviewPublicId,
            @Valid @RequestBody EditReviewRequest request) {

        ProfessionalReview review = editService.editReview(
                appointmentPublicId,
                reviewPublicId,
                new HubClinicalEditService.ReviewEdit(
                        request.notes(),
                        request.queryDetail(),
                        request.reason(),
                        request.expectedUpdatedAt()));

        return ResponseEntity.ok(row(review));
    }

    @GetMapping("/follow-ups")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).RELEASE_BUNDLE_READ)")
    @Operation(summary = "Follow-ups for one consultation, with what the hub can edit")
    @ApiResponse(responseCode = "200", description = "Follow-ups returned.")
    public ResponseEntity<List<HubFollowUpRow>> followUps(@PathVariable String appointmentPublicId) {
        return ResponseEntity.ok(editService.followUps(appointmentPublicId)
                .stream()
                .map(HubOversightController::row)
                .toList());
    }

    @PutMapping("/follow-ups/{followUpPublicId}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).HUB_CLINICAL_EDIT)")
    @Transactional
    @Operation(
            summary = "Edit a follow-up's scheduling",
            description = """
                    Replaces the scheduling fields: preferred date and time, mode, status,
                    scheduled and completed dates, and coordination notes. Send every
                    field, including the ones you did not change.

                    The recommendation, interval and timeframe are the doctor's and cannot
                    be changed here. Neither can a follow-up the doctor marked as not
                    required.

                    **Requires** `hub.clinical_edit`.
                    """)
    @ApiResponse(responseCode = "200", description = "Edited.")
    public ResponseEntity<HubFollowUpRow> editFollowUp(
            @PathVariable String appointmentPublicId,
            @PathVariable String followUpPublicId,
            @Valid @RequestBody EditFollowUpRequest request) {

        FollowUp followUp = editService.editFollowUp(
                appointmentPublicId,
                followUpPublicId,
                new HubClinicalEditService.FollowUpEdit(
                        request.preferredDate(),
                        request.preferredTime(),
                        request.consultationMode(),
                        request.status(),
                        request.scheduledDate(),
                        request.completedDate(),
                        request.notes(),
                        request.reason(),
                        request.expectedUpdatedAt()));

        return ResponseEntity.ok(row(followUp));
    }

    @GetMapping("/edits")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).RELEASE_BUNDLE_READ)")
    @Operation(
            summary = "Edit history for one consultation",
            description = """
                    Every hub edit to this consultation's reviews and follow-ups, newest
                    first. Rows sharing an `editGroup` were saved together.
                    """)
    @ApiResponse(responseCode = "200", description = "History returned.")
    public ResponseEntity<List<EditRevisionRow>> edits(@PathVariable String appointmentPublicId) {
        return ResponseEntity.ok(editService.history(appointmentPublicId)
                .stream()
                .map(HubOversightController::row)
                .toList());
    }

    private static HubFollowUpRow row(FollowUp f) {
        return new HubFollowUpRow(
                f.getPublicId(),
                f.getRecommendation(),
                f.getReviewInterval(),
                f.getExpectedTimeframe(),
                f.getPreferredDate(),
                f.getPreferredTime(),
                f.getConsultationMode() == null ? null : f.getConsultationMode().name(),
                f.getStatus() == null ? null : f.getStatus().name(),
                f.getScheduledDate(),
                f.getCompletedDate(),
                f.getNotes(),
                f.getStatus() != FollowUpStatus.NOT_REQUIRED,
                f.getUpdatedAt());
    }

    private static EditRevisionRow row(ClinicalEditRevision r) {
        return new EditRevisionRow(
                r.getPublicId(),
                r.getEditGroup(),
                r.getTargetType().name(),
                r.getTargetPublicId(),
                r.getFieldName(),
                r.getOldValue(),
                r.getNewValue(),
                r.getReason(),
                r.getEditedByName() != null ? r.getEditedByName() : r.getEditedBy(),
                r.getCreatedAt());
    }

    public record EditReviewRequest(
            @Size(max = 10_000) String notes,
            @Size(max = 10_000) String queryDetail,
            @NotBlank @Size(min = 10, max = 500) String reason,
            LocalDateTime expectedUpdatedAt) {
    }

    public record EditFollowUpRequest(
            LocalDate preferredDate,
            LocalTime preferredTime,
            Modality consultationMode,
            FollowUpStatus status,
            LocalDate scheduledDate,
            LocalDate completedDate,
            @Size(max = 10_000) String notes,
            @NotBlank @Size(min = 10, max = 500) String reason,
            LocalDateTime expectedUpdatedAt) {
    }

    public record HubFollowUpRow(
            String followUpPublicId,
            String recommendation,
            String reviewInterval,
            String expectedTimeframe,
            LocalDate preferredDate,
            LocalTime preferredTime,
            String consultationMode,
            String status,
            LocalDate scheduledDate,
            LocalDate completedDate,
            String notes,
            boolean editable,
            LocalDateTime updatedAt) {
    }

    public record EditRevisionRow(
            String revisionPublicId,
            String editGroup,
            String targetType,
            String targetPublicId,
            String fieldName,
            String oldValue,
            String newValue,
            String reason,
            String editedBy,
            LocalDateTime editedAt) {
    }

    private static HubReviewRow row(ProfessionalReview r) {
        String issueNumber = null;
        String documentStatus = null;

        if (r.getPrescription() != null) {
            issueNumber = r.getPrescription().getIssueNumber();
            documentStatus = r.getPrescription().getStatus().name();
        } else if (r.getInvestigation() != null) {
            issueNumber = r.getInvestigation().getIssueNumber();
            documentStatus = r.getInvestigation().getStatus().name();
        }

        String state = r.getReviewer() == null ? "UNASSIGNED"
                : r.getSubmittedAt() != null ? "SUBMITTED"
                : r.getOpenedAt() != null ? "IN_REVIEW"
                : "NOT_OPENED";

        return new HubReviewRow(
                r.getPublicId(),
                r.getReviewType().name(),
                issueNumber,
                documentStatus,
                r.getReviewer() == null ? null : r.getReviewer().getFullName(),
                state,
                r.getOutcome() == null ? null : r.getOutcome().name(),
                r.getNotes(),
                Boolean.TRUE.equals(r.getQueryRaised()),
                r.getQueryDetail(),
                r.getAssignedAt(),
                r.getOpenedAt(),
                r.getSubmittedAt(),
                r.getSubmittedToHubAt(),
                r.getUpdatedAt());
    }

    public record HubReviewRow(
            String reviewPublicId,
            String reviewType,
            String documentIssueNumber,
            String documentStatus,
            String reviewerName,
            String state,
            String outcome,
            String notes,
            boolean queryRaised,
            String queryDetail,
            LocalDateTime assignedAt,
            LocalDateTime openedAt,
            LocalDateTime submittedAt,
            LocalDateTime submittedToHubAt,
            LocalDateTime updatedAt) {
    }
}
