package com.fnph.telepsychiatric.centre;

public enum ReferralStatus {
    /** Being prepared. Not visible to FNPH. */
    DRAFT,
    SUBMITTED,
    /** An appointment request has been made against it. */
    SCHEDULED,
    /** FNPH sent it back for more information. */
    RETURNED,
    COMPLETED,
    WITHDRAWN
}
