package com.fnph.telepsychiatric.audit;

/**
 * What an audit row records.
 *
 * A closed set rather than free text. An auditor asking "show me every time
 * someone opened a clinician's dashboard" needs a value to filter on, and free
 * text produces six spellings of the same event within a year.
 */
public enum AuditAction {

    // Access
    LOGIN_SUCCESS,
    LOGIN_FAILED,
    LOGOUT,
    SESSION_REVOKED,
    PASSWORD_CHANGED,
    PASSWORD_RESET,
    MFA_ENROLLED,
    MFA_RESET,

    // Supervision
    VIEW_AS_STARTED,
    VIEW_AS_ENDED,
    VIEW_AS_ACTION,

    // Authorisation
    ROLES_ASSIGNED,
    USER_INVITED,
    USER_ACTIVATED,
    USER_DEACTIVATED,
    ACCESS_DENIED,
    CROSS_TENANT_BLOCKED,

    // Clinical
    CLINICAL_NOTE_SIGNED,
    PRESCRIPTION_ISSUED,
    PRESCRIPTION_SUPERSEDED,
    INVESTIGATION_ISSUED,
    REVIEW_SUBMITTED,
    BUNDLE_RELEASED,
    CONSULTATION_TERMINATED,

    // Records
    RECORD_VIEWED,
    RECORD_CREATED,
    RECORD_UPDATED,
    DOCUMENT_DOWNLOADED,
    DOCUMENT_REVOKED,

    // Money
    PAYMENT_VERIFIED,
    PAYMENT_RECONCILED,
    PAYMENT_REFUNDED,
    WALLET_CREDITED,
    WALLET_DEBITED,

    // Governance
    CONFIGURATION_CHANGED,
    CENTRE_CAPABILITY_CHANGED,
    CENTRE_STATUS_CHANGED,
    EHR_IMPORT_ACTIVATED
}
