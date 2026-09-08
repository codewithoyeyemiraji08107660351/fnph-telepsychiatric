package com.fnph.telepsychiatric.centre;

/**
 * How soon the centre believes the patient should be seen.
 *
 * There is deliberately no EMERGENCY value. The service excludes emergencies,
 * severe agitation, acute psychosis and immediate risk, and offering the option
 * would invite a centre to route one here instead of to emergency care.
 */
public enum ReferralUrgency {
    ROUTINE,
    /** Seen sooner if a slot allows. Not a clinical priority claim. */
    SOON
}
