package com.fnph.telepsychiatric.centre;

import java.util.Set;

/**
 * Who at a centre acts on a delivered care bundle.
 *
 * Each records its own action. The role decides which a person may record,
 * so a pharmacist cannot close the laboratory's work by accident.
 */
public enum CentreCapability {
    COORDINATION(Set.of("CENTRE_HUB_COORDINATOR", "CENTRE_ASSISTANT_COORDINATOR"), Content.ANY),
    PHARMACY(Set.of("CENTRE_PHARMACY"), Content.PRESCRIPTION),
    LABORATORY(Set.of("CENTRE_LABORATORY"), Content.INVESTIGATION),
    HIM(Set.of("CENTRE_HIM"), Content.ANY);

    /** What a bundle must contain to be this service's work. */
    public enum Content { ANY, PRESCRIPTION, INVESTIGATION }

    public final Set<String> roleCodes;
    public final Content needs;

    CentreCapability(Set<String> roleCodes, Content needs) {
        this.roleCodes = roleCodes;
        this.needs = needs;
    }
}
