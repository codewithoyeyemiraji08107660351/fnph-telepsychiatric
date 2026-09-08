package com.fnph.telepsychiatric.scheduling;

/**
 * Which pathway a published day serves.
 *
 * Separate schedules rather than one shared pool. The specification gives
 * centres a dedicated schedule configured by the FNPH Hub Coordinator, and a
 * shared pool would let patient bookings consume every centre slot on a busy
 * morning, or the reverse.
 */
public enum ScheduleAudience {
    FNPH_PATIENT,
    CENTRE
}
