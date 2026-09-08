package com.fnph.telepsychiatric.scheduling;

public enum SlotState {
    AVAILABLE,
    /** Reserved while the patient pays. Returns to AVAILABLE if the hold lapses. */
    HELD,
    BOOKED,
    /** Withdrawn by the Hub Coordinator: no doctor, a public holiday, a fault. */
    BLOCKED
}
