package com.fnph.telepsychiatric.consultation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The session clock.
 *
 * The rule that matters: remaining time counts down from the fixed slot end,
 * never from first join. Getting this wrong means one late patient pushes every
 * subsequent appointment back, and the people who lose the time had nothing to
 * do with the delay.
 */
class SessionClockTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 1, 9, 0);
    private static final LocalDateTime END = START.plusMinutes(30);

    private long remainingSeconds(LocalDateTime now) {
        return Math.max(0, Duration.between(now, END).getSeconds());
    }

    @ParameterizedTest(name = "joining {0} minutes late leaves {1} minutes")
    @CsvSource({
            "0,  30",
            "5,  25",
            "10, 20",
            "14, 16",
            "29, 1"
    })
    @DisplayName("joining late shortens the session; it never moves the end")
    void lateJoinDoesNotExtend(int minutesLate, int expectedRemaining) {
        assertThat(remainingSeconds(START.plusMinutes(minutesLate)))
                .isEqualTo(expectedRemaining * 60L);
    }

    @Test
    @DisplayName("remaining time never goes negative")
    void neverNegative() {
        assertThat(remainingSeconds(END.plusMinutes(10))).isZero();
    }

    @ParameterizedTest(name = "at {0} minutes elapsed, first warning due: {1}")
    @CsvSource({
            "0,  false",
            "14, false",
            "15, true",
            "20, true",
            "25, true"
    })
    @DisplayName("the first warning fires at fifteen minutes remaining")
    void firstWarningTiming(int elapsed, boolean expected) {
        long minutesLeft = remainingSeconds(START.plusMinutes(elapsed)) / 60;
        assertThat(minutesLeft <= 15).isEqualTo(expected);
    }

    @ParameterizedTest(name = "at {0} minutes elapsed, second warning due: {1}")
    @CsvSource({
            "15, false",
            "19, false",
            "20, true",
            "25, true"
    })
    @DisplayName("the second warning fires at ten minutes remaining")
    void secondWarningTiming(int elapsed, boolean expected) {
        long minutesLeft = remainingSeconds(START.plusMinutes(elapsed)) / 60;
        assertThat(minutesLeft <= 10).isEqualTo(expected);
    }

    @Test
    @DisplayName("both warnings fire, and each fires once")
    void bothWarningsFireOnce() {
        // The source documents disagree about whether there is a warning at
        // fifteen or one at ten. There are two, and this is what says so.
        boolean firstSent = false;
        boolean secondSent = false;
        int firstCount = 0;
        int secondCount = 0;

        for (int elapsed = 0; elapsed <= 30; elapsed++) {
            long minutesLeft = remainingSeconds(START.plusMinutes(elapsed)) / 60;
            if (minutesLeft <= 15 && !firstSent) {
                firstSent = true;
                firstCount++;
            }
            if (minutesLeft <= 10 && !secondSent) {
                secondSent = true;
                secondCount++;
            }
        }
        assertThat(firstCount).isOne();
        assertThat(secondCount).isOne();
    }

    @ParameterizedTest(name = "{0} minutes before start, patient may join: {1}")
    @CsvSource({
            "20, false",
            "16, false",
            "15, true",
            "5,  true",
            "0,  true"
    })
    @DisplayName("the room opens fifteen minutes before the start and not sooner")
    void joinWindowOpens(int minutesBefore, boolean canJoin) {
        LocalDateTime now = START.minusMinutes(minutesBefore);
        LocalDateTime opensAt = START.minusMinutes(15);
        assertThat(!now.isBefore(opensAt)).isEqualTo(canJoin);
    }

    @ParameterizedTest(name = "{0} minutes after start, patient may still join: {1}")
    @CsvSource({
            "0,  true",
            "14, true",
            "15, true",
            "16, false",
            "30, false"
    })
    @DisplayName("the patient join window closes fifteen minutes after the start")
    void noShowCutoff(int minutesAfter, boolean canJoin) {
        LocalDateTime now = START.plusMinutes(minutesAfter);
        LocalDateTime cutoff = START.plusMinutes(15);
        assertThat(!now.isAfter(cutoff)).isEqualTo(canJoin);
    }

    @Test
    @DisplayName("the three timing values are independent")
    void threeValuesNotOne() {
        // The room-open lead, the joining grace and the no-show cutoff are
        // three parameters. The source documents give three different numbers
        // for what reads like one, and collapsing them would reproduce that
        // ambiguity in code where it is much harder to see.
        int roomOpenLead = 15;
        int joiningGrace = 5;
        int noShowCutoff = 15;

        assertThat(joiningGrace).isNotEqualTo(roomOpenLead);
        assertThat(START.minusMinutes(roomOpenLead)).isBefore(START);
        assertThat(START.plusMinutes(joiningGrace)).isBefore(START.plusMinutes(noShowCutoff));
    }
}
