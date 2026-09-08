package com.fnph.telepsychiatric.ehr;

public enum VerificationRequestStatus {
    SUBMITTED,
    WITH_HIM,
    WITH_ICT,
    /** Matched to a real record; an account was created. */
    RESOLVED,
    /** No such patient, or the claim could not be substantiated. */
    REJECTED
}
