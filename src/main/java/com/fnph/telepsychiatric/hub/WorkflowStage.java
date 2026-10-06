package com.fnph.telepsychiatric.hub;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.clinical.FollowUp;
import com.fnph.telepsychiatric.clinical.FollowUpStatus;
import com.fnph.telepsychiatric.clinical.ReleaseBundle;
import com.fnph.telepsychiatric.clinical.ReviewDigest;

import java.util.List;

/**
 * Where one consultation is in its journey, from the hub's point of view.
 *
 * Derived on read from the appointment, its bundle, reviews and follow-ups.
 * Nothing stores it, so it can never drift from the records it describes.
 */
public enum WorkflowStage {
    /** Slot held while payment completes. */
    PAYMENT,
    /** Paid, waiting on the Hub Coordinator. */
    APPROVAL,
    /** Approved, team and room assigned, session not yet started. */
    SCHEDULED,
    IN_SESSION,
    /** Session over; the doctor's note or outputs are still outstanding. */
    DOCUMENTATION,
    /** A pharmacy or laboratory review is still open. */
    REVIEWS,
    /** Everything is in. The hub has not released yet. */
    AWAITING_RELEASE,
    /** Held deliberately by the hub, with a reason. */
    HELD,
    /** Released, with a follow-up still to schedule or attend. */
    FOLLOW_UP,
    CLOSED,
    /** Did not run: rejected, cancelled, no-show, rescheduled or expired. */
    ENDED;

    public static WorkflowStage of(Appointment appointment, ReleaseBundle bundle,
                                   List<ReviewDigest> reviews, List<FollowUp> followUps) {
        switch (appointment.getStatus()) {
            case SLOT_HELD:
                return PAYMENT;
            case AWAITING_APPROVAL:
                return APPROVAL;
            case APPROVED:
                return SCHEDULED;
            case IN_PROGRESS:
                return IN_SESSION;
            case COMPLETED:
                break;
            default:
                return ENDED;
        }

        if (bundle == null) {
            return DOCUMENTATION;
        }
        switch (bundle.getStatus()) {
            case BLOCKED:
                return HELD;
            case READY:
                return AWAITING_RELEASE;
            case INCOMPLETE:
                return reviews.stream().anyMatch(ReviewDigest::pending) ? REVIEWS : DOCUMENTATION;
            case RELEASED:
            default:
                break;
        }

        boolean followUpOpen = followUps.stream().anyMatch(f ->
                f.getStatus() == FollowUpStatus.RECOMMENDED
                        || f.getStatus() == FollowUpStatus.SCHEDULED);
        return followUpOpen ? FOLLOW_UP : CLOSED;
    }
}
