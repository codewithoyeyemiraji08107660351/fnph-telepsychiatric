package com.fnph.telepsychiatric.session;

public enum LoginOutcome {
    SUCCESS,
    MFA_REQUIRED,
    MFA_FAILED,
    BAD_CREDENTIALS,
    UNKNOWN_USERNAME,
    ACCOUNT_LOCKED,
    ACCOUNT_INACTIVE,
    ACCOUNT_NOT_ACTIVATED,
    NO_ROLE_ASSIGNED,
    RATE_LIMITED
}
