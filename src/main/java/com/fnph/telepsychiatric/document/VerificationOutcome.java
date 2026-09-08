package com.fnph.telepsychiatric.document;

public enum VerificationOutcome {
    VALID,
    EXPIRED,
    REVOKED,
    SUPERSEDED,
    /** No document with that token. Also what a guessed token returns. */
    NOT_FOUND
}
