package com.fnph.telepsychiatric.session;

public enum AccountTokenPurpose {
    /** First sign-in for an invited staff or centre account. */
    ACTIVATION,

    /** Requested by the user, or issued by an administrator on their behalf. */
    PASSWORD_RESET
}
