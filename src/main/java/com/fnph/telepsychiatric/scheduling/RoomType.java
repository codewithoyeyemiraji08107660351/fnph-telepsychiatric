package com.fnph.telepsychiatric.scheduling;

public enum RoomType {
    PATIENT_SERVICE,
    CENTRE_CONSULTATION,
    /** Used when an assigned room becomes unavailable mid-session. */
    CONTINGENCY
}
