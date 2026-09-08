package com.fnph.telepsychiatric.document;

public enum DocumentType {
    PRESCRIPTION,
    INVESTIGATION_REQUEST,
    /** The signed consultation note, where a patient copy is released. */
    CLINICAL_SUMMARY,
    FOLLOW_UP_RECOMMENDATION
}
