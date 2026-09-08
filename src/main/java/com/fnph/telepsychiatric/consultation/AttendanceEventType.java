package com.fnph.telepsychiatric.consultation;

public enum AttendanceEventType {
    JOINED,
    LEFT,
    RECONNECTED,
    MODALITY_CHANGED,
    /** Clinician confirmed the person on screen is the patient on the record. */
    IDENTITY_CONFIRMED,
    WARNING_SENT,
    TERMINATED,
    ROOM_CLOSED
}
