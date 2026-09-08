package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * One expected part of a release bundle.
 *
 * A row per component turns completeness into a query instead of a rule
 * rewritten for each case.
 */
@Entity
@Table(name = "release_bundle_components")
@Getter
@Setter
public class ReleaseBundleComponent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bundle_id", nullable = false)
    private ReleaseBundle bundle;

    @Enumerated(EnumType.STRING)
    @Column(name = "component_type", nullable = false, length = 30)
    private ComponentType componentType;

    @Column(name = "component_id")
    private Long componentId;

    @Column(name = "is_required", nullable = false)
    private Boolean isRequired = true;

    @Column(name = "is_complete", nullable = false)
    private Boolean isComplete = false;

    /**
     * The doctor decided none was needed.
     *
     * Distinct from "not done yet". Without the distinction, a consultation
     * that legitimately produced no investigation request would sit blocked
     * forever waiting for a document nobody intends to write.
     */
    @Column(name = "not_required", nullable = false)
    private Boolean notRequired = false;

    @Column(name = "not_required_reason", length = 500)
    private String notRequiredReason;

    @Column(name = "blocked_reason", length = 500)
    private String blockedReason;

    /** Done, or deliberately not needed. Either way it does not hold the bundle. */
    public boolean isSettled() {
        return Boolean.TRUE.equals(isComplete) || Boolean.TRUE.equals(notRequired);
    }
}
