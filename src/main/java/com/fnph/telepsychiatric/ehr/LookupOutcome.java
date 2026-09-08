package com.fnph.telepsychiatric.ehr;

public enum LookupOutcome {
    MATCHED,
    /** No record with that EHR number in the active snapshot. */
    NOT_FOUND,
    /** Number exists, but the corroborating detail did not match. */
    CORROBORATION_FAILED,
    /** Number exists but is already bound to an active account. */
    ALREADY_ENROLLED,
    /** Record present but marked inactive in the snapshot. */
    RECORD_INACTIVE,
    RATE_LIMITED,
    /** No snapshot has been activated yet. */
    NO_ACTIVE_IMPORT
}
