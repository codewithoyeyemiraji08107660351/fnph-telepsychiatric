package com.fnph.telepsychiatric.helpdesk;

public enum TicketPriority {
    LOW,
    NORMAL,
    /** Something in progress is blocked: a consultation starting shortly. */
    HIGH,
    /** Service-affecting. Not a clinical urgency; those are escalated, not prioritised. */
    URGENT
}
