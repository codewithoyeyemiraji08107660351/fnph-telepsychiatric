package com.fnph.telepsychiatric.clinical;

/**
 * The four states Module 1 requires on the Nursing and HIM dashboards.
 *
 * Derived from timestamps rather than stored. A stored status and a set of
 * timestamps drift apart, and the timestamps are what an audit reconstructs
 * from.
 */
public enum WorkQueueState {
    UNTREATED,
    IN_PROGRESS,
    TREATED,
    EXCEPTION
}