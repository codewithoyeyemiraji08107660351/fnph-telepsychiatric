package com.fnph.telepsychiatric.center;

public enum CentreStatus {

    /**
     * Created, no coordinator yet. Must not appear in a booking list: a centre
     * with nobody to receive a released bundle would strand clinical output.
     */
    SETUP,

    ACTIVE,

    /**
     * Barred from booking, with staff and history intact. Reversible. Used
     * while a problem is investigated, without losing anything.
     */
    SUSPENDED
}
