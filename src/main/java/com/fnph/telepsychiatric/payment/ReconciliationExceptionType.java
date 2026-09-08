package com.fnph.telepsychiatric.payment;

public enum ReconciliationExceptionType {
    /** Initiated here, still pending at Remita past the expected window. */
    STILL_PENDING,
    /** Remita reports failure; slot selection was never unlocked. */
    FAILED_AT_PROVIDER,
    REVERSED_AT_PROVIDER,
    /** Remita reports a payment this system has no order for. */
    UNMATCHED_AT_PROVIDER,
    /**
     * Remita reports a different amount from the order.
     *
     * Never auto-accepted. Paying less than the fee is not a rounding issue,
     * and paying more means the patient is owed a credit.
     */
    AMOUNT_MISMATCH,
    /** Marked successful here but Remita has no record of it. */
    MISSING_AT_PROVIDER
}
