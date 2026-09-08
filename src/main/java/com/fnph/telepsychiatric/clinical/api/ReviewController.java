package com.fnph.telepsychiatric.clinical.api;

import com.fnph.telepsychiatric.clinical.*;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/reviews")
@RequiredArgsConstructor
@Tag(name = "Professional Review")
public class ReviewController {

    private final ProfessionalReviewService reviewService;

    @GetMapping("/pharmacy/queue")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).REVIEW_PHARMACY)")
    @Operation(
            summary = "My pharmacy review queue",
            description = """
                    Prescriptions assigned to you, oldest first.

                    **Assigned, not shared.** The Hub Coordinator named you at approval and
                    the team was told who was covering this consultation, so the work lands
                    with you rather than in a pool anyone can pick from.

                    **The doctor's clinical note is not included and is not available.**
                    Pharmacy sees patient identity, permitted biodata, vitals and the
                    submitted material. That boundary is in the permission matrix and in
                    what this endpoint loads.

                    **Requires** `review.pharmacy`.
                    """)
    @ApiResponse(responseCode = "200", description = "Queue returned.")
    public ResponseEntity<List<Map<String, Object>>> pharmacyQueue() {
        return ResponseEntity.ok(toQueue(
                reviewService.queueFor(CurrentUser.require().getUserId())));
    }

    @GetMapping("/laboratory/queue")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).REVIEW_LABORATORY)")
    @Operation(
            summary = "My laboratory review queue",
            description = """
                    Investigation requests assigned to you, oldest first.

                    Same boundary as pharmacy: the doctor's clinical note is not included
                    and is not available.

                    **Requires** `review.laboratory`.
                    """)
    @ApiResponse(responseCode = "200", description = "Queue returned.")
    public ResponseEntity<List<Map<String, Object>>> laboratoryQueue() {
        return ResponseEntity.ok(toQueue(
                reviewService.queueFor(CurrentUser.require().getUserId())));
    }

    @PostMapping("/{reviewPublicId}/open")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).REVIEW_READ)")
    @Operation(
            summary = "Open a review",
            description = """
                    Records that you have started, which is how the coordinator can tell a
                    queue that is being worked from one that is stuck.

                    **Requires** `review.read`, and the review must be assigned to you.
                    """)
    @ApiResponse(responseCode = "204", description = "Opened.")
    public ResponseEntity<Void> open(@PathVariable String reviewPublicId) {
        reviewService.open(reviewPublicId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{reviewPublicId}/submit")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).REVIEW_SUBMIT_TO_HUB)")
    @Operation(
            summary = "Submit a review to the Hub Coordinator",
            description = """
                    Completes the review and moves it forward.

                    **Forward is the only direction.** There is no endpoint that returns a
                    document to the doctor, and raising a concern does not reopen it for
                    editing. If a prescription needs changing, the clarification happens in
                    the multidisciplinary team and the doctor issues a new one that
                    supersedes it.

                    That is slower than an edit, and it is the only version in which the
                    prescriber decided what the patient takes.

                    Submitting marks the bundle component complete. When every component is
                    settled, the coordinator is notified that the bundle is ready.

                    **Requires** `review.submit_to_hub`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Submitted to the Hub Coordinator."),
            @ApiResponse(responseCode = "400",
                    description = "Already submitted, or a concern was raised with no detail.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> submit(@PathVariable String reviewPublicId,
                                       @Valid @RequestBody SubmitReviewRequest request) {
        reviewService.submit(reviewPublicId, ReviewOutcome.valueOf(request.outcome()),
                request.notes(), request.queryDetail());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/queries")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).REVIEW_READ)")
    @Operation(
            summary = "Concerns raised by reviewers",
            description = """
                    Everything a pharmacist or technician flagged, for the Hub Coordinator
                    to take to the multidisciplinary team.

                    **This is the only route a concern travels.** It does not reach the
                    doctor through the system. Whether the prescription changes is decided
                    in the team, and the change is a new document the doctor authors.

                    **Requires** `review.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Raised concerns returned.")
    public ResponseEntity<List<Map<String, Object>>> queries() {
        return ResponseEntity.ok(toQueue(reviewService.raisedQueries()));
    }

    private List<Map<String, Object>> toQueue(List<ProfessionalReview> reviews) {
        return reviews.stream().map(r -> {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("publicId", r.getPublicId());
            row.put("reviewType", r.getReviewType().name());
            row.put("assignedAt", r.getAssignedAt());
            row.put("openedAt", r.getOpenedAt());
            row.put("submittedAt", r.getSubmittedAt());
            row.put("queryRaised", Boolean.TRUE.equals(r.getQueryRaised()));
            row.put("queryDetail", r.getQueryDetail());
            row.put("documentType", r.getPrescription() != null ? "PRESCRIPTION" : "INVESTIGATION");
            row.put("issueNumber", r.getPrescription() != null
                    ? r.getPrescription().getIssueNumber()
                    : r.getInvestigation().getIssueNumber());
            // Deliberately no clinical note. Pharmacy and laboratory do not see it.
            return row;
        }).toList();
    }
}
