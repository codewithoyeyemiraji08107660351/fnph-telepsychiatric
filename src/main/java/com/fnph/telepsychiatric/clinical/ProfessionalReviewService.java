package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.notification.InAppNotificationService;
import com.fnph.telepsychiatric.notification.NotificationType;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.user.Users;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Pharmacy and laboratory review.
 *
 * <h2>Reviews travel forward only</h2>
 *
 * A submitted review goes to the Hub Coordinator. There is no method here that
 * returns one to the doctor, and there is no state that would let one get
 * there, because a prescription that can be changed by anyone other than the
 * prescriber is not a prescription.
 *
 * A pharmacist who finds a dosing error records a query. It reaches the Hub
 * Coordinator, the conversation happens in the multidisciplinary team, and if
 * the prescription needs changing the doctor writes a new one that supersedes
 * the old. That is slower than an edit and it is the only version in which the
 * prescriber decided.
 *
 * <h2>Reviewers do not see the clinical note</h2>
 *
 * Pharmacy and laboratory see patient identity, permitted biodata, vitals and
 * the submitted material. Not the doctor's note. That is enforced by the
 * permission matrix and by what this service loads: nothing here reads a note,
 * so there is nothing to leak into a response by accident.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProfessionalReviewService {

    private final ProfessionalReviewRepository reviewRepository;
    private final ReleaseService releaseService;
    private final InAppNotificationService notifications;
    private final AuditService auditService;

    /**
     * Creates the review task when a doctor issues a prescription.
     *
     * Assigned to the pharmacist the Hub Coordinator named at approval, not
     * dropped into a shared pool. The team was told who was covering this
     * consultation, and the work should land with them.
     */
    @Transactional
    public ProfessionalReview assignForPrescription(Prescription prescription, Users pharmacist) {
        ProfessionalReview review = new ProfessionalReview();
        review.setReviewType(ReviewType.PHARMACY);
        review.setPrescription(prescription);
        review.setReviewer(pharmacist);
        review.setAssignedAt(LocalDateTime.now());
        ProfessionalReview saved = reviewRepository.save(review);

        if (pharmacist != null) {
            notifications.notifyUser(pharmacist, NotificationType.PRESCRIPTION_RELEASED,
                    "A prescription is ready for your review",
                    "A prescription from a consultation you were assigned to is waiting for "
                            + "transcription and professional verification.",
                    "/reviews/pharmacy", "Prescription", prescription.getId());
        }
        return saved;
    }

    @Transactional
    public ProfessionalReview assignForInvestigation(Investigation investigation, Users technician) {
        ProfessionalReview review = new ProfessionalReview();
        review.setReviewType(ReviewType.LABORATORY);
        review.setInvestigation(investigation);
        review.setReviewer(technician);
        review.setAssignedAt(LocalDateTime.now());
        ProfessionalReview saved = reviewRepository.save(review);

        if (technician != null) {
            notifications.notifyUser(technician, NotificationType.INVESTIGATION_RELEASED,
                    "An investigation request is ready for your review",
                    "An investigation request from a consultation you were assigned to is "
                            + "waiting for transcription and review.",
                    "/reviews/laboratory", "Investigation", investigation.getId());
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ProfessionalReview> queueFor(Long reviewerId) {
        return reviewRepository.findOpenQueue(reviewerId);
    }

    @Transactional
    public ProfessionalReview open(String reviewPublicId) {
        ProfessionalReview review = require(reviewPublicId);
        if (review.getOpenedAt() == null) {
            review.setOpenedAt(LocalDateTime.now());
            reviewRepository.save(review);
        }
        return review;
    }

    /**
     * Submits a completed review forward to the Hub Coordinator.
     *
     * There is no parameter for sending it back, and no branch that does.
     * Raising a query records a concern and still moves forward; it does not
     * reopen the document for the doctor to edit.
     */
    @Transactional
    public ProfessionalReview submit(String reviewPublicId, ReviewOutcome outcome,
                                     String notes, String queryDetail) {
        ProfessionalReview review = require(reviewPublicId);

        if (review.getSubmittedAt() != null) {
            throw new IllegalStateException("That review has already been submitted");
        }
        if (outcome == ReviewOutcome.QUERY_RAISED
                && (queryDetail == null || queryDetail.isBlank())) {
            throw new IllegalArgumentException(
                    "Describe the concern. It goes to the Hub Coordinator for the "
                            + "multidisciplinary team, so it has to be readable by someone "
                            + "who was not in the consultation.");
        }

        LocalDateTime now = LocalDateTime.now();
        review.setOutcome(outcome);
        review.setNotes(notes);
        review.setQueryRaised(outcome == ReviewOutcome.QUERY_RAISED);
        review.setQueryDetail(queryDetail);
        review.setSubmittedAt(now);
        // Forward, always. Never back to the doctor.
        review.setSubmittedToHubAt(now);
        reviewRepository.save(review);

        if (review.getPrescription() != null) {
            review.getPrescription().setStatus(ClinicalDocumentStatus.REVIEWED);
            releaseService.markComponentComplete(
                    review.getPrescription().getBundle(), ComponentType.PRESCRIPTION);
        }
        if (review.getInvestigation() != null) {
            review.getInvestigation().setStatus(ClinicalDocumentStatus.REVIEWED);
            releaseService.markComponentComplete(
                    review.getInvestigation().getBundle(), ComponentType.INVESTIGATION);
        }

        notifications.notifyRole("HUB_COORDINATOR", null, NotificationType.SUPPORT_TICKET_UPDATE,
                outcome == ReviewOutcome.QUERY_RAISED
                        ? "A review raised a concern"
                        : "A review has been completed",
                outcome == ReviewOutcome.QUERY_RAISED
                        ? "A %s review raised a concern that needs the multidisciplinary team."
                                .formatted(review.getReviewType().name().toLowerCase())
                        : "A %s review is complete and the bundle may be ready to release."
                                .formatted(review.getReviewType().name().toLowerCase()),
                "/hub/releases", "ProfessionalReview", review.getId());

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.REVIEW_SUBMITTED)
                .entityType("ProfessionalReview")
                .entityId(review.getId())
                .details(review.getReviewType() + " review submitted to the Hub Coordinator: "
                        + outcome)
                .reason(queryDetail)
                .build());

        log.info("{} review {} submitted to hub: {}",
                review.getReviewType(), review.getPublicId(), outcome);
        return review;
    }

    @Transactional(readOnly = true)
    public List<ProfessionalReview> raisedQueries() {
        return reviewRepository.findRaisedQueries();
    }

    private ProfessionalReview require(String publicId) {
        ProfessionalReview review = reviewRepository.findByPublicId(publicId)
                .orElseThrow(() -> new IllegalArgumentException("No such review"));

        CurrentUser.get().ifPresent(principal -> {
            if (review.getReviewer() != null
                    && !review.getReviewer().getId().equals(principal.getUserId())) {
                throw new IllegalArgumentException(
                        "That review is assigned to another professional");
            }
        });
        return review;
    }
}
