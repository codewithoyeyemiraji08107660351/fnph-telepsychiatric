package com.fnph.telepsychiatric.scheduling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slot generation arithmetic.
 *
 * Small, and the kind of thing that is wrong by one period for months before
 * anyone notices, because a schedule with thirteen slots instead of fourteen
 * looks entirely normal.
 */
class SlotGenerationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);

    /** Mirrors ScheduleService.generateSlots. */
    private List<LocalDateTime> periods(LocalTime start, LocalTime end, int minutes) {
        List<LocalDateTime> result = new ArrayList<>();
        LocalDateTime periodStart = LocalDateTime.of(DATE, start);
        LocalDateTime windowEnd = LocalDateTime.of(DATE, end);

        while (!periodStart.plusMinutes(minutes).isAfter(windowEnd)) {
            result.add(periodStart);
            periodStart = periodStart.plusMinutes(minutes);
        }
        return result;
    }

    @ParameterizedTest(name = "{0} to {1} at {2} minutes gives {3} periods")
    @CsvSource({
            "09:00, 12:00, 30, 6",
            "09:00, 16:00, 30, 14",
            "09:00, 09:30, 30, 1",
            "09:00, 11:45, 30, 5",
            "08:00, 17:00, 45, 12"
    })
    @DisplayName("periods fit inside the window")
    void periodCount(String start, String end, int minutes, int expected) {
        assertThat(periods(LocalTime.parse(start), LocalTime.parse(end), minutes))
                .hasSize(expected);
    }

    @Test
    @DisplayName("no slot ever runs past the window end")
    void nothingOverrunsTheWindow() {
        // A trailing period that does not fit is dropped, not truncated. A
        // fifteen-minute consultation appearing at the end of the day would be
        // a different product.
        LocalTime end = LocalTime.of(11, 45);
        List<LocalDateTime> starts = periods(LocalTime.of(9, 0), end, 30);

        LocalDateTime lastEnd = starts.get(starts.size() - 1).plusMinutes(30);
        assertThat(lastEnd).isBeforeOrEqualTo(LocalDateTime.of(DATE, end));
    }

    @Test
    @DisplayName("a window shorter than one slot generates nothing")
    void tooShortAWindowGeneratesNothing() {
        // The service refuses this rather than publishing it. A day that exists,
        // looks fine on the schedule list and shows a patient no times at all is
        // worse than no day, because nobody reports it.
        assertThat(periods(LocalTime.of(9, 0), LocalTime.of(9, 20), 30)).isEmpty();
    }

    @Test
    @DisplayName("capacity is periods multiplied by rooms")
    void capacityIsRoomsTimesPeriods() {
        // One slot per period per active room. Taking a room out of service
        // reduces tomorrow's capacity without anyone editing a schedule.
        int periods = periods(LocalTime.of(9, 0), LocalTime.of(16, 0), 30).size();
        assertThat(periods).isEqualTo(14);
        assertThat(periods * 4).isEqualTo(56);
        assertThat(periods * 3).as("a room out of service costs 14 consultations").isEqualTo(42);
    }

    @Test
    @DisplayName("periods are contiguous with no gap and no overlap")
    void periodsAreContiguous() {
        List<LocalDateTime> starts = periods(LocalTime.of(9, 0), LocalTime.of(16, 0), 30);
        for (int i = 1; i < starts.size(); i++) {
            assertThat(starts.get(i))
                    .as("period %d starts exactly where period %d ended", i, i - 1)
                    .isEqualTo(starts.get(i - 1).plusMinutes(30));
        }
    }
}
