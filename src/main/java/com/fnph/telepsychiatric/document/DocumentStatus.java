package com.fnph.telepsychiatric.document;

public enum DocumentStatus {
    ACTIVE,
    /** Past its validity. A saved copy still verifies, and verifies as expired. */
    EXPIRED,
    /** Withdrawn deliberately. Distinct from expired: someone decided this. */
    REVOKED,
    /** Replaced by a corrected version. The replacement is named. */
    SUPERSEDED
}
