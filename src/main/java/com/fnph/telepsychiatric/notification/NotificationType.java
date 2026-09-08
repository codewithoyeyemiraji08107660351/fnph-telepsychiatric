package com.fnph.telepsychiatric.notification;

public enum NotificationType {
    EHR_VERIFICATION_RESULT,
    PAYMENT_SUCCESS,
    PAYMENT_FAILURE,
    BOOKING_SUBMITTED,
    APPOINTMENT_APPROVED,
    APPOINTMENT_REJECTED,
    APPOINTMENT_REASSIGNED,
    APPOINTMENT_CANCELLED,
    APPOINTMENT_RESCHEDULED,
    APPOINTMENT_REMINDER,
    JOIN_WINDOW_OPEN,
    CONSULTATION_READY,
    NO_SHOW_RECORDED,
    PRESCRIPTION_RELEASED,
    INVESTIGATION_RELEASED,
    FOLLOW_UP_RECOMMENDED,
    CENTRE_WALLET_ALERT,
    SUPPORT_TICKET_UPDATE,
    SYSTEM_ALERT,

    /** A non-refunded payment was held as credit against the next booking. */
    CREDIT_ISSUED,

    /** Remita reported an amount other than the order's. Finance decides. */
    PAYMENT_AMOUNT_MISMATCH,

    /** A booking request is waiting on the Hub Coordinator's desk. */
    BOOKING_AWAITING_APPROVAL,

    /** An assignee has been given work on a confirmed appointment. */
    APPOINTMENT_ASSIGNED,

    /** A reserved slot returned to the pool because payment did not complete. */
    SLOT_HOLD_EXPIRED
}
