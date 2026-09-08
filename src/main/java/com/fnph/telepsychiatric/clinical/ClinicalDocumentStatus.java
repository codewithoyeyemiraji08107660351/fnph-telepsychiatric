package com.fnph.telepsychiatric.clinical;

/**
 * Prescription and investigation lifecycle.
 *
 * There is no transition back to DRAFT from PENDING_REVIEW. Reviews travel
 * forward to the Hub Coordinator and never return to the doctor through the
 * system, which matches the approved workflow. A clinical correction is issued
 * as a new document that supersedes the previous one.
 */
public enum ClinicalDocumentStatus {
    DRAFT,
    NOT_REQUIRED,
    PENDING_REVIEW,
    REVIEWED,
    RELEASED,
    EXPIRED,
    SUPERSEDED,
    REVOKED
}
