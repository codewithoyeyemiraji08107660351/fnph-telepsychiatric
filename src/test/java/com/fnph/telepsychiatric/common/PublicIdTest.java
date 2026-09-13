package com.fnph.telepsychiatric.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PublicIdTest {

    @RepeatedTest(50)
    @DisplayName("generates a 26-character identifier")
    void isTwentySixCharacters() {
        assertThat(PublicId.generate()).hasSize(26);
    }

    @Test
    @DisplayName("does not collide across 200,000 identifiers")
    void doesNotCollide() {
        Set<String> seen = new HashSet<>();
        IntStream.range(0, 200_000).forEach(i ->
                assertThat(seen.add(PublicId.generate()))
                        .as("collision at iteration %d", i)
                        .isTrue());
    }

    @Test
    @DisplayName("sorts by creation time")
    void isTimeOrdered() {
        // Lexicographic ordering matching creation order is what makes the
        // identifier usable in an ordered index rather than scattering writes.
        String earlier = PublicId.generate(1_600_000_000_000L);
        String later = PublicId.generate(1_700_000_000_000L);
        assertThat(earlier).isLessThan(later);
    }

    @Test
    @DisplayName("excludes characters that can be misread")
    void hasNoAmbiguousCharacters() {
        // Crockford base32 omits I, L, O and U, so an identifier cannot be
        // misread between 1 and I or 0 and O when read aloud or retyped, and
        // cannot accidentally spell a word.
        String sample = IntStream.range(0, 2_000)
                .mapToObj(i -> PublicId.generate())
                .reduce("", String::concat);

        assertThat(sample).doesNotContain("I", "L", "O", "U");
    }

    @Test
    @DisplayName("rejects malformed values")
    void rejectsMalformed() {
        assertThat(PublicId.isValid(null)).isFalse();
        assertThat(PublicId.isValid("")).isFalse();
        assertThat(PublicId.isValid("TOO-SHORT")).isFalse();
        assertThat(PublicId.isValid("I1ARZ3NDEKTSV4RRFFQ69G5FAV")).isFalse();
        assertThat(PublicId.isValid(PublicId.generate())).isTrue();
    }
}
