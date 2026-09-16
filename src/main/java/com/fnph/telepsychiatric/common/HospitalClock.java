package com.fnph.telepsychiatric.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The hospital's local time, for the few inputs that are expressed in it.
 *
 * Timestamps are stored in UTC and the JVM runs in UTC. A schedule window is
 * entered as hospital time ("09:00 to 16:00"), so it has to be converted before
 * it becomes a slot. Combining the date and time directly stored 09:00 as
 * 09:00 UTC, and every published slot opened an hour late in Kaduna.
 */
public final class HospitalClock {

    /** West Africa Time. No daylight saving. */
    public static final ZoneId ZONE = ZoneId.of("Africa/Lagos");

    private HospitalClock() {
    }

    /** Today's date at the hospital. */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /** A hospital-local date and time as the UTC value the database stores. */
    public static LocalDateTime toUtc(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}