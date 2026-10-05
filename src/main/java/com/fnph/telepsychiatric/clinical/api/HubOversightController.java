package com.fnph.telepsychiatric.clinical.api;

import com.fnph.telepsychiatric.appointment.AppointmentRepository;
import com.fnph.telepsychiatric.clinical.ProfessionalReview;
import com.fnph.telepsychiatric.clinical.ProfessionalReviewRepository;
import com.fnph.telepsychiatric.clinical.ReleaseBundleRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Hub Coordinator oversight of one consultation's multidisciplinary work.
 * Read-only in this step; editing arrives with versioned history.
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
                r.getSubmittedToHubAt());
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
            LocalDateTime submittedToHubAt) {
    }
}
