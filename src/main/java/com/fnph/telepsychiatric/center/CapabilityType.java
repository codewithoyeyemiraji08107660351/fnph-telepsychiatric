package com.fnph.telepsychiatric.center;

/**
 * The optional local professional roles a centre may have activated.
 *
 * Every centre starts with all three disabled. The specification is explicit
 * that initial centre access is the Centre Hub Coordinator and one Assistant
 * only, and that these are activated later by the Central Administrator after
 * staffing and capability review.
 */
public enum CapabilityType {

    /** Enables the CENTRE_PHARMACY role at this centre. */
    PHARMACY,

    /** Enables the CENTRE_LABORATORY role at this centre. */
    LABORATORY,

    /** Enables the CENTRE_HIM role at this centre. */
    HIM
}
