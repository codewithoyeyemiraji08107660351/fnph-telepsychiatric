package com.fnph.telepsychiatric.payment;

public enum WebhookState {
    /** Stored, not yet interpreted. */
    RECEIVED,
    PROCESSED,
    /** Interpretation failed. Retried, then left for Finance to look at. */
    FAILED,
    /** A duplicate, or an event this system does not act on. */
    IGNORED
}
