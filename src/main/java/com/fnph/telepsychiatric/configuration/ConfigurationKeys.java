package com.fnph.telepsychiatric.configuration;

/**
 * Compile-time constants for the seeded configuration keys.
 *
 * A typo in a key string returns a default silently, which for a fee or a
 * session length is a defect nobody notices until a patient is affected.
 * ConfigurationKeyTest asserts every constant here exists in the database and
 * every seeded key has a constant, so the two cannot drift.
 */
public final class ConfigurationKeys {

    private ConfigurationKeys() {
    }

    // --- Session timing -------------------------------------------------
    // Three separate keys. The source documents give three different numbers
    // for what reads like one setting; merging them here would reproduce the
    // ambiguity in code.

    /** How early before the slot start the join control activates. */
    public static final String ROOM_OPEN_LEAD_MINUTES = "room_open_lead_minutes";

    /** How late a patient may join before being flagged late. */
    public static final String JOINING_GRACE_MINUTES = "joining_grace_minutes";

    /** How long after the start the link deactivates and a no-show is recorded. */
    public static final String NO_SHOW_CUTOFF_MINUTES = "no_show_cutoff_minutes";

    public static final String SESSION_MINUTES_FNPH = "session_minutes_fnph";
    public static final String SESSION_MINUTES_CENTRE = "session_minutes_centre";
    public static final String WARNING_ONE_MINUTES_REMAINING = "warning_one_minutes_remaining";
    public static final String WARNING_TWO_MINUTES_REMAINING = "warning_two_minutes_remaining";
    public static final String RECORDING_ENABLED = "recording_enabled";

    // --- Scheduling ------------------------------------------------------
    public static final String SLOT_HOLD_TTL_MINUTES = "slot_hold_ttl_minutes";
    public static final String CANCELLATION_NOTICE_HOURS = "cancellation_notice_hours";

    // --- Money -----------------------------------------------------------
    public static final String CONSULTATION_FEE_NGN = "consultation_fee_ngn";
    public static final String CENTRE_BOOKING_CHARGE_NGN = "centre_booking_charge_ngn";
    public static final String WALLET_WARNING_PERCENT = "wallet_warning_percent";
    public static final String WALLET_CRITICAL_PERCENT = "wallet_critical_percent";

    // --- Documents -------------------------------------------------------
    public static final String PRESCRIPTION_VALIDITY_DAYS = "prescription_validity_days";
    public static final String INVESTIGATION_VALIDITY_DAYS = "investigation_validity_days";
    public static final String PRESCRIPTION_MAX_DOWNLOADS = "prescription_max_downloads";
    public static final String INVESTIGATION_MAX_DOWNLOADS = "investigation_max_downloads";

    // --- Contact and support ---------------------------------------------
    public static final String CLINICAL_EMERGENCY_NUMBER = "clinical_emergency_number";
    public static final String HELPDESK_EMAIL = "helpdesk_email";
    public static final String HELPDESK_FIRST_RESPONSE_MINUTES = "helpdesk_first_response_minutes";
    public static final String HELPDESK_RESOLUTION_TARGET_HOURS = "helpdesk_resolution_target_hours";

    /**
     * Where a clinical concern goes. Governance-owned, because who answers a
     * patient's question about their treatment is a clinical decision, not an
     * operational one.
     */
    public static final String HELPDESK_CLINICAL_ESCALATION_ROLE = "helpdesk_clinical_escalation_role";

    public static final String HELPDESK_TECHNICAL_ESCALATION_ROLE = "helpdesk_technical_escalation_role";
    public static final String HELPDESK_PAYMENT_ESCALATION_ROLE = "helpdesk_payment_escalation_role";

    // --- Operational ------------------------------------------------------
    public static final String SESSION_INACTIVITY_TIMEOUT_MINUTES = "session_inactivity_timeout_minutes";
    public static final String MAX_UPLOAD_SIZE_MB = "max_upload_size_mb";
    public static final String EHR_IMPORT_STALENESS_WARNING_DAYS = "ehr_import_staleness_warning_days";
}
