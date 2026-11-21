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

    /**
     * Done, deliberately not needed, or never asked for. None of these holds
     * the bundle.
     *
     * A prescription or investigation the doctor never issued has nothing for
     * a pharmacist or laboratory technician to review. It used to count as
     * outstanding until the doctor also clicked "not needed", so a
     * consultation with no investigation sat on the release desk waiting on
     * the laboratory. Issuing one creates its review and sets isRequired,
     * which makes it outstanding again until the review is submitted.
     */
    public boolean isSettled() {
        if (Boolean.TRUE.equals(isComplete) || Boolean.TRUE.equals(notRequired)) {
            return true;
        }
        return !Boolean.TRUE.equals(isRequired) && isReviewedComponent();
    }

    /** Settled because nothing was issued, not because anything was done. */
    public boolean isNotRequested() {
        return !Boolean.TRUE.equals(isComplete)
                && !Boolean.TRUE.equals(notRequired)
                && !Boolean.TRUE.equals(isRequired)
                && isReviewedComponent();
    }

    private boolean isReviewedComponent() {
        return componentType == ComponentType.PRESCRIPTION
                || componentType == ComponentType.INVESTIGATION;
    }
}
