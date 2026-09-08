package com.fnph.telepsychiatric.clinical;

public enum ReviewOutcome {
    /** Transcribed and professionally verified. Nothing to raise. */
    VERIFIED,

    /**
     * A concern the reviewer wants recorded.
     *
     * It reaches the Hub Coordinator, not the doctor. The conversation happens
     * in the multidisciplinary team and any correction is a new document the
     * doctor authors, because a prescription that could be changed by anyone
     * other than the prescriber is not a prescription.
     */
    QUERY_RAISED
}
