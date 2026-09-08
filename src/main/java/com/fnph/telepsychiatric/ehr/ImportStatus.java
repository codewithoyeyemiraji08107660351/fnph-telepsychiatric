package com.fnph.telepsychiatric.ehr;

public enum ImportStatus {
    /** Received, checksum computed, not yet parsed. */
    UPLOADED,
    VALIDATING,
    /** Parsed cleanly. Not yet the source enrolment matches against. */
    VALIDATED,
    /** One or more rows failed. Nothing was loaded; the report says which. */
    REJECTED,
    /** The snapshot enrolment currently matches against. Exactly one at a time. */
    ACTIVE,
    /** Replaced by a newer snapshot. Retained for audit and diffing. */
    SUPERSEDED
}
