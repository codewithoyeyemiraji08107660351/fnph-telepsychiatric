package com.fnph.telepsychiatric.session;

public enum MfaType {
    /** Time-based one-time password, RFC 6238. The only type enrolled today. */
    TOTP,

    /**
     * Reserved. Not enabled: SMS delivery in Nigeria is unreliable enough that
     * making it the second factor would lock staff out of a clinical system
     * during a network outage, and SIM swap is a real attack here.
     */
    SMS,

    EMAIL
}
