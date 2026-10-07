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
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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

        // New work for the component: a bundle that was READY is not any more.
        releaseService.markComponentPending(prescription.getBundle(), ComponentType.PRESCRIPTION);

        if (pharmacist == null) {
            alertUnassigned(ReviewType.PHARMACY, saved, prescription.getBundle());
        } else {
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

        releaseService.markComponentPending(investigation.getBundle(), ComponentType.INVESTIGATION);

        if (technician == null) {
            alertUnassigned(ReviewType.LABORATORY, saved, investigation.getBundle());
        } else {
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

        // A superseded or revoked document keeps that status. Overwriting it with
        // REVIEWED would bring a replaced prescription back to life.
        if (review.getPrescription() != null) {
            if (review.getPrescription().getStatus() == ClinicalDocumentStatus.PENDING_REVIEW) {
                review.getPrescription().setStatus(ClinicalDocumentStatus.REVIEWED);
            }
            completeIfAllReviewed(review.getPrescription().getBundle(),
                    ReviewType.PHARMACY, ComponentType.PRESCRIPTION);
        }
        if (review.getInvestigation() != null) {
            if (review.getInvestigation().getStatus() == ClinicalDocumentStatus.PENDING_REVIEW) {
                review.getInvestigation().setStatus(ClinicalDocumentStatus.REVIEWED);
            }
            completeIfAllReviewed(review.getInvestigation().getBundle(),
                    ReviewType.LABORATORY, ComponentType.INVESTIGATION);
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

    /**
     * Moves every unsubmitted review of one kind on a bundle to another
     * professional, including reviews nobody was assigned to.
     *
     * Used by the Hub Coordinator when the pharmacist or laboratory technician
     * on a consultation changes, or when a review is sitting with someone who
     * is away. Submitted reviews stay with whoever submitted them: that is the
     * record of who verified the document. Reviews of superseded or revoked
     * documents are left alone; there is nothing left to verify.
     *
     * @return how many reviews moved
     */
    @Transactional
    public int reassignOpen(Long bundleId, ReviewType type, Users to, String reason) {
        if (bundleId == null) {
            return 0;
        }
        List<ProfessionalReview> moving = openLive(bundleId, type);
        LocalDateTime now = LocalDateTime.now();
        int moved = 0;

        for (ProfessionalReview review : moving) {
            Users previous = review.getReviewer();
            if (previous != null && to != null && previous.getId().equals(to.getId())) {
                continue;
            }
            review.setReviewer(to);
            review.setAssignedAt(now);
            // The new reviewer has not looked at it yet.
            review.setOpenedAt(null);
            reviewRepository.save(review);
            moved++;

            if (previous != null) {
                notifications.notifyUser(previous, NotificationType.APPOINTMENT_REASSIGNED,
                        "A review has been moved to a colleague",
                        "The Hub Coordinator has moved a " + kind(type)
                                + " review from your queue to someone else. "
                                + "You do not need to do anything further on it.",
                        queueUrl(type), "ProfessionalReview", review.getId());
            }
            if (to != null) {
                notifications.notifyUser(to, type == ReviewType.PHARMACY
                                ? NotificationType.PRESCRIPTION_RELEASED
                                : NotificationType.INVESTIGATION_RELEASED,
                        "A " + kind(type) + " review has been assigned to you",
                        "The Hub Coordinator has assigned you a " + kind(type)
                                + " review from a consultation. It is waiting in your queue.",
                        queueUrl(type), "ProfessionalReview", review.getId());
            }

            auditService.record(AuditService.AuditEvent.builder()
                    .action(AuditAction.HUB_REVIEW_REASSIGNED)
                    .entityType("ProfessionalReview")
                    .entityId(review.getId())
                    .details(type + " review moved from "
                            + (previous == null ? "nobody" : previous.getFullName())
                            + " to " + (to == null ? "nobody" : to.getFullName()))
                    .reason(reason)
                    .build());
        }

        log.info("{} open {} reviews on bundle {} now with {}",
                moved, type, bundleId, to == null ? "nobody" : to.getPublicId());
        return moved;
    }

    /** Unsubmitted reviews of one kind on a bundle whose document is still live. */
    @Transactional(readOnly = true)
    public List<ProfessionalReview> openLive(Long bundleId, ReviewType type) {
        return reviewRepository.findAllByBundleId(bundleId).stream()
                .filter(r -> r.getReviewType() == type)
                .filter(r -> r.getSubmittedAt() == null)
                .filter(ProfessionalReviewService::isLive)
                .toList();
    }

    /**
     * Whether the review's document still needs verifying. A superseded,
     * revoked or expired document will never be released, so an open review on
     * it must not hold the bundle.
     */
    public static boolean isLive(ProfessionalReview review) {
        ClinicalDocumentStatus status = review.getPrescription() != null
                ? review.getPrescription().getStatus()
                : review.getInvestigation() != null ? review.getInvestigation().getStatus() : null;
        return status == null || !DEAD.contains(status);
    }

    private static final Set<ClinicalDocumentStatus> DEAD = EnumSet.of(
            ClinicalDocumentStatus.SUPERSEDED,
            ClinicalDocumentStatus.REVOKED,
            ClinicalDocumentStatus.EXPIRED);

    /**
     * Marks the component complete only when every live review of that kind on
     * the bundle has been submitted.
     *
     * Previously the first submitted review completed the whole component, so
     * a consultation with two prescriptions went READY, and could be released,
     * while the second was still waiting for the pharmacist.
     */
    private void completeIfAllReviewed(ReleaseBundle bundle, ReviewType type, ComponentType component) {
        if (bundle == null) {
            return;
        }
        if (openLive(bundle.getId(), type).isEmpty()) {
            releaseService.markComponentComplete(bundle, component);
        } else {
            releaseService.markComponentPending(bundle, component);
        }
    }

    /** FNPH bundles only: centre bundles have their own coordinators and desk. */
    private void alertUnassigned(ReviewType type, ProfessionalReview review, ReleaseBundle bundle) {
        if (bundle == null || bundle.getAppointment() == null) {
            return;
        }
        notifications.notifyRole("HUB_COORDINATOR", null, NotificationType.SUPPORT_TICKET_UPDATE,
                "A " + kind(type) + " review has nobody assigned",
                "No " + (type == ReviewType.PHARMACY ? "pharmacist" : "laboratory technician")
                        + " was on the team for this consultation, so the review is in nobody's "
                        + "queue. Assign one from the release desk.",
                "/hub/releases", "ProfessionalReview", review.getId());
    }

    private static String kind(ReviewType type) {
        return type == ReviewType.PHARMACY ? "pharmacy" : "laboratory";
    }

    private static String queueUrl(ReviewType type) {
        return type == ReviewType.PHARMACY ? "/reviews/pharmacy" : "/reviews/laboratory";
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
