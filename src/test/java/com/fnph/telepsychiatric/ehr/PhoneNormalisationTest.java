package com.fnph.telepsychiatric.ehr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phone normalisation, which is small and matters more than it looks.
 *
 * The same Nigerian number is written several ways across hospital systems.
 * Comparing them literally would make corroboration fail for a patient whose
 * number is entirely correct, sending them into the exception queue over a
 * formatting difference and adding manual work for HIM on every import.
 */
class PhoneNormalisationTest {

    private String normalise(String raw) throws Exception {
        Method m = com.fnph.telepsychiatric.ehr.EhrImportService.class
                .getDeclaredMethod("normalisePhone", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, raw);
    }

    @ParameterizedTest(name = "{0} normalises to {1}")
    @CsvSource({
            "08012345678,     012345678",
            "+2348012345678,  012345678",
            "2348012345678,   012345678",
            "0801 234 5678,   012345678",
            "0801-234-5678,   012345678",
            "(0801) 234 5678, 012345678"
    })
    @DisplayName("the same number written differently normalises the same way")
    void equivalentFormatsMatch(String raw, String expected) throws Exception {
        assertThat(normalise(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("all the common formats of one number agree with each other")
    void allFormatsAgree() throws Exception {
        String canonical = normalise("08012345678");
        for (String variant : new String[]{"+2348012345678", "2348012345678",
                "0801 234 5678", "0801-234-5678"}) {
            assertThat(normalise(variant))
                    .as("%s must match 08012345678", variant)
                    .isEqualTo(canonical);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "12345", "abc", "0801"})
    @DisplayName("too short or non-numeric returns nothing rather than a bad match")
    void rejectsUnusableInput(String raw) throws Exception {
        assertThat(normalise(raw)).isNull();
    }

    @Test
    @DisplayName("null is handled without throwing")
    void handlesNull() throws Exception {
        assertThat(normalise(null)).isNull();
    }

    @Test
    @DisplayName("different numbers do not collide")
    void differentNumbersStayDifferent() throws Exception {
        assertThat(normalise("08012345678")).isNotEqualTo(normalise("08087654321"));
    }
}
