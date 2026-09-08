package com.fnph.telepsychiatric.authz;

import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * A single capability, named module.action.
 *
 * The catalogue is fixed by migration rather than created at runtime. A
 * permission that no code checks is dead weight, and a permission created
 * outside a migration would not exist in another environment.
 */
@Entity
@Table(name = "permissions")
@Getter
@Setter
public class Permission extends BaseEntity {

    /** e.g. appointment.approve. Matches a constant in {@link Permissions}. */
    @Column(name = "code", nullable = false, length = 80)
    private String code;

    /** Grouping for the administration screen, e.g. schedule, finance. */
    @Column(name = "module", nullable = false, length = 40)
    private String module;

    /** Shown to the Central Administrator when reviewing the matrix. */
    @Column(name = "description", nullable = false, length = 500)
    private String description;

    /**
     * Whether exercising this permission changes state.
     *
     * Decides what is available during supervised access, which is read-only.
     * Defaults to true, so a permission added without thought is excluded from
     * supervision rather than silently included.
     */
    @Column(name = "is_mutating", nullable = false)
    private Boolean isMutating = true;
}
