package com.fnph.telepsychiatric.appointment;

/**
 * The appointment lifecycle, in the sequence FNPH confirmed.
 *
 * <pre>
 *   verified payment + chosen slot ──> AWAITING_APPROVAL ──approve──> APPROVED ──> IN_PROGRESS ──> COMPLETED
 *       │                      │                          │
 *       └──hold lapses──> EXPIRED                          ├──> CANCELLED
 *                              └──reject──> REJECTED       └──> NO_SHOW
 * </pre>
 *
 * The patient pays, chooses a time, and only then does the Hub Coordinator
 * review. The booking transaction briefly uses {@code SLOT_HELD} while it
 * links the verified payment, intake and selected time.
 */
public enum Status {

    /**
     * Slot reserved while a booking is being linked to a verified payment.
     *
     * Time-limited. An abandoned payment must not hold a clinic slot
     * indefinitely, or the schedule shows as full while nobody is booked.
     */
    SLOT_HELD,

    /** The hold lapsed before payment completed. The slot returned to the pool. */
    EXPIRED,

    /**
     * Paid and waiting on the Hub Coordinator.
     *
     * The slot is BOOKED at this point, not merely held: the patient has paid
     * and must not lose the time while a coordinator gets to their queue.
     */
    AWAITING_APPROVAL,

    /** Approved, team and room assigned, wallet debited, everyone notified. */
    APPROVED,

    /**
     * Turned down by the Hub Coordinator.
     *
     * The slot is released and the amount paid stays on the patient's wallet
     * for their next booking. Payment is non-refundable, but nobody is charged
     * for a consultation that did not happen.
     */
    REJECTED,

    RESCHEDULED,
    CANCELLED,
    IN_PROGRESS,
    COMPLETED,

    /** No join by the configured cutoff. The link deactivated. */
    NO_SHOW
}
