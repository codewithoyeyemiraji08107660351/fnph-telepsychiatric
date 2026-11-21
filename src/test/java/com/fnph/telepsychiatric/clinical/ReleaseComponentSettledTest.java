package com.fnph.telepsychiatric.clinical;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A review nobody asked for must not hold the release desk. One that was
 * asked for holds it until it is done.
 */
class ReleaseComponentSettledTest {

    private static ReleaseBundleComponent component(ComponentType type, boolean required,
                                                    boolean complete, boolean notRequired) {
        ReleaseBundleComponent c = new ReleaseBundleComponent();
        c.setComponentType(type);
        c.setIsRequired(required);
        c.setIsComplete(complete);
        c.setNotRequired(notRequired);
        return c;
    }

    @Test
    @DisplayName("a prescription or investigation never issued does not hold the bundle")
    void notIssuedIsSettled() {
        assertThat(component(ComponentType.INVESTIGATION, false, false, false).isSettled()).isTrue();
        assertThat(component(ComponentType.PRESCRIPTION, false, false, false).isSettled()).isTrue();
        assertThat(component(ComponentType.INVESTIGATION, false, false, false).isNotRequested()).isTrue();
    }

    @Test
    @DisplayName("an issued one holds the bundle until its review is submitted")
    void issuedWaitsForReview() {
        assertThat(component(ComponentType.INVESTIGATION, true, false, false).isSettled()).isFalse();
        assertThat(component(ComponentType.INVESTIGATION, true, true, false).isSettled()).isTrue();
        assertThat(component(ComponentType.INVESTIGATION, true, true, false).isNotRequested()).isFalse();
    }

    @Test
    @DisplayName("the clinical note and follow-up still wait for the doctor")
    void doctorItemsStillRequired() {
        assertThat(component(ComponentType.CLINICAL_NOTE, true, false, false).isSettled()).isFalse();
        assertThat(component(ComponentType.FOLLOW_UP, false, false, false).isSettled()).isFalse();
        assertThat(component(ComponentType.FOLLOW_UP, false, false, true).isSettled()).isTrue();
    }
}
