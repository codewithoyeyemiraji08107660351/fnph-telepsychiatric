package com.fnph.telepsychiatric.document;

public enum DownloadOutcome {
    ALLOWED,
    /** The allowance is used. The document is still readable on screen. */
    REFUSED_LIMIT_REACHED,
    REFUSED_EXPIRED,
    REFUSED_REVOKED,
    REFUSED_NOT_OWNER
}
