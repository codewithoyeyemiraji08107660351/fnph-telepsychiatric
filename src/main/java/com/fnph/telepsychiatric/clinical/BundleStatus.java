package com.fnph.telepsychiatric.clinical;

public enum BundleStatus {
    /** Something is still outstanding. */
    INCOMPLETE,
    /** Every required component is done or explicitly not required. */
    READY,
    RELEASED,
    /** Held deliberately by the Hub Coordinator, with a reason. */
    BLOCKED
}
