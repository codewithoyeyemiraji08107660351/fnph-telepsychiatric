package com.fnph.telepsychiatric.helpdesk;

public enum TicketStatus {
    OPEN,
    IN_PROGRESS,
    /** Waiting on the person who raised it. The clock is theirs, not ours. */
    AWAITING_REQUESTER,
    ESCALATED,
    RESOLVED,
    CLOSED
}
