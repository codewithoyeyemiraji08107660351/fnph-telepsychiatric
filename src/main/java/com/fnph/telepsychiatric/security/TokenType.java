package com.fnph.telepsychiatric.security;

public enum TokenType {

    /** Short-lived. Carries role and tenant claims. */
    ACCESS,

    /** Rotated on every use. Exchanged for a new pair, never sent to an API. */
    REFRESH,

    /**
     * Issued after a correct password, before the second factor. Accepted only
     * on the MFA endpoints and carries no authority claims, so holding one
     * without completing the challenge grants nothing.
     */
    CHALLENGE
}
