package com.fnph.telepsychiatric.user;

import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.common.Roles;
import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.patient.Patient;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Table(name = "users", uniqueConstraints = {
        @UniqueConstraint(columnNames = "email"),
        @UniqueConstraint(columnNames = "username")
})
@Getter
@Setter
public class Users extends BaseEntity implements UserDetails {

    @Column(name = "username", nullable = false, length = 50)
    private String username;

    @Column(name = "email", nullable = false, length = 100)
    private String email;

    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "first_name", nullable = false, length = 50)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 50)
    private String lastName;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "address", length = 200)
    private String address;

    @Column(name = "role", nullable = false)
    @Enumerated(EnumType.STRING)
    private Roles role;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "mfa_enabled", nullable = false)
    private Boolean mfaEnabled = false;


    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "failed_login_attempts")
    private Integer failedLoginAttempts = 0;

    @Column(name = "account_locked")
    private Boolean accountLocked = false;

    @Column(name = "lock_expiry")
    private LocalDateTime lockExpiry;

    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id")
    private Center centre;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_roles_override",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_override")
    )
    @Enumerated(EnumType.STRING)
    @ElementCollection(targetClass = Roles.class, fetch = FetchType.EAGER)
    @CollectionTable(name = "user_role_overrides", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role_name")
    private Set<Roles> roleOverrides = new HashSet<>();

    @Column(name = "refresh_token")
    private String refreshToken;

    @Column(name = "token_expiry")
    private LocalDateTime tokenExpiry;

    @Column(name = "login_type")
    @Enumerated(EnumType.STRING)
    private LoginType loginType = LoginType.USERNAME;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        Set<Roles> allRoles = new HashSet<>(roleOverrides);
        allRoles.add(role);

        return allRoles.stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r.name()))
                .collect(Collectors.toSet());
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        // For patients, username can be EHR number
        if (patient != null && LoginType.EHR_NUMBER.equals(loginType)) {
            return patient.getEhrNumber();
        }
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !accountLocked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return isActive;
    }

    // Helper method to get display name
    public String getDisplayName() {
        return firstName + " " + lastName;
    }

    // Helper method to get full name
    public String getFullName() {
        return firstName + " " + lastName;
    }

    // Check if user is a patient
    public boolean isPatient() {
        return Roles.PATIENT.equals(role);
    }

    // Check if user belongs to a centre
    public boolean isCentreStaff() {
        return centre != null &&
                (Roles.CENTRE_HUB_COORDINATOR.equals(role) ||
                        Roles.CENTRE_ASSISTANT_COORDINATOR.equals(role) ||
                        Roles.CENTRE_PHARMACY.equals(role) ||
                        Roles.CENTRE_LABORATORY.equals(role) ||
                        Roles.CENTRE_HIM.equals(role));
    }

    // Check if user is FNPH Core Engine staff
    public boolean isFnphStaff() {
        return centre == null &&
                !Roles.PATIENT.equals(role) &&
                !Roles.CENTRE_HUB_COORDINATOR.equals(role) &&
                !Roles.CENTRE_ASSISTANT_COORDINATOR.equals(role) &&
                !Roles.CENTRE_PHARMACY.equals(role) &&
                !Roles.CENTRE_LABORATORY.equals(role) &&
                !Roles.CENTRE_HIM.equals(role);
    }

    public boolean hasRole(Roles roleToCheck) {
        if (this.role.equals(roleToCheck)) {
            return true;
        }
        return roleOverrides.contains(roleToCheck);
    }
    public boolean hasAnyRole(Roles... roles) {
        for (Roles roleToCheck : roles) {
            if (hasRole(roleToCheck)) {
                return true;
            }
        }
        return false;
    }
}
