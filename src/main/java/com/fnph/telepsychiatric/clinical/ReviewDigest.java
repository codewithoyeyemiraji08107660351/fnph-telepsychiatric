package com.fnph.telepsychiatric.clinical;

import java.time.LocalDateTime;

/** The few facts about a review that the hub workflow board needs. */
public record ReviewDigest(
        Long bundleId,
        ReviewType reviewType,
        Long reviewerId,
        LocalDateTime openedAt,
        LocalDateTime submittedAt,
        Boolean queryRaised) {

    /**
     * For the JPQL projection. A review hangs off a prescription or an
     * investigation, so exactly one of the two bundle ids is set.
     */
    public ReviewDigest(Long prescriptionBundleId, Long investigationBundleId,
                        ReviewType reviewType, Long reviewerId,
                        LocalDateTime openedAt, LocalDateTime submittedAt,
                        Boolean queryRaised) {
        this(prescriptionBundleId != null ? prescriptionBundleId : investigationBundleId,
                reviewType, reviewerId, openedAt, submittedAt, queryRaised);
    }

    public boolean pending() {
        return submittedAt == null;
    }

    public boolean unassigned() {
        return reviewerId == null && submittedAt == null;
    }

    public boolean query() {
        return Boolean.TRUE.equals(queryRaised);
    }


}
