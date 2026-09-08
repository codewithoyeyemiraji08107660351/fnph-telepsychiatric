package com.fnph.telepsychiatric.security;

import com.fnph.telepsychiatric.authz.RoleScope;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The authenticated principal.
 *
 * Deliberately not the Users entity. Putting a JPA entity in the security
 * context means every authorisation check risks a lazy-load against a session
 * that is no longer open, and it puts patient-linked fields somewhere they can
 * be serialised by accident. This is a flat, immutable snapshot built once at
 * authentication.
 *
 * Authorities carry both forms:
 *   ROLE_DOCTOR            for coarse path matching in SecurityConfig
 *   appointment.approve    for method security, which is where real decisions
 *                          are made
 *
 * centreId and patientId are on the principal so tenant scoping and
 * own-record scoping never need a database round trip to establish who is
 * asking.
 */
@Getter
@Builder
@RequiredArgsConstructor
public class SecurityUser implements UserDetails {

    private final Long userId;
    private final String publicId;
    private final String username;
    private final String password;
    private final String displayName;

    private final boolean active;
    private final boolean locked;
    private final boolean mfaEnabled;
    private final boolean mustChangePassword;

    /** Set for centre staff only. The tenant every query is constrained to. */
    private final Long centreId;

    /** Set for patient accounts only. The record they may read. */
    private final Long patientId;

    private final RoleScope scope;
    private final String primaryRole;

    /** Where the client sends this user after sign-in. No role selector. */
    private final String dashboardRoute;

    private final Set<String> roles;
    private final Set<String> permissions;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        roles.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
        permissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    public boolean hasPermission(String code) {
        return permissions.contains(code);
    }

    public boolean isCentreScoped() {
        return scope == RoleScope.CENTRE;
    }

    public boolean isPatientScoped() {
        return scope == RoleScope.PATIENT;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !locked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
