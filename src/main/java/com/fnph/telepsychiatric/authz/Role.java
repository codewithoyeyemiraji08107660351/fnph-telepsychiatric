package com.fnph.telepsychiatric.authz;

import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

/**
 * A named bundle of permissions.
 *
 * Seeded by V5 and marked isSystem, which means it may be reviewed and its
 * permissions adjusted but it may not be deleted: existing audit and clinical
 * history references it.
 */
@Entity
@Table(name = "roles")
@Getter
@Setter
public class Role extends BaseEntity {

    /** Stable machine identifier, e.g. HUB_COORDINATOR. Never renamed. */
    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    private RoleScope scope;

    /**
     * Where authentication lands a user holding this role as their primary.
     * The specification requires an ordinary user to be routed straight to
     * their dashboard with no role selector shown, so the destination has to
     * be a property of the role rather than a branch in the client.
     */
    @Column(name = "dashboard_route", nullable = false, length = 100)
    private String dashboardRoute;

    @Column(name = "is_system", nullable = false)
    private Boolean isSystem = true;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "role_permission",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions = new HashSet<>();
}
