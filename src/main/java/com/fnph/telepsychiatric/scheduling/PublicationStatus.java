package com.fnph.telepsychiatric.scheduling;

public enum PublicationStatus {
    /** Slots generated, invisible to patients. */
    DRAFT,
    PUBLISHED,
    /** Withdrawn. Existing bookings stand; no new ones are taken. */
    WITHDRAWN
}
