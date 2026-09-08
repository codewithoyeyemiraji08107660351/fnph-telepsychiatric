package com.fnph.telepsychiatric.helpdesk;

/**
 * A closed set, deliberately.
 *
 * Free-text categorisation would produce six spellings of "cannot log in"
 * within a year and make the reporting FNPH asked for meaningless. More
 * importantly, {@link #CLINICAL_CONCERN} has to be a value the system can
 * recognise, because it is the one category the helpdesk must never answer.
 */
public enum TicketCategory {

    ACCESS_AND_SIGN_IN,
    ENROLMENT,
    BOOKING,
    PAYMENT,
    /** Video, audio, connection. Escalates to ICT. */
    TECHNICAL_FAULT,
    DOCUMENT_ACCESS,

    /**
     * Anything about the patient's health, symptoms, medication or treatment.
     *
     * The helpdesk cannot resolve one of these. It is escalated, and the
     * emergency guidance is attached automatically. A support agent has no
     * clinical permission and no clinical training, and answering would put
     * them in the position of giving medical advice.
     */
    CLINICAL_CONCERN,

    OTHER
}
