package com.fnph.telepsychiatric.user;

import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.common.SoftDeletableEntity;
import com.fnph.telepsychiatric.authz.UserRole;
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
import java.util.List;
import java.util.Set;

/**
 * The roleOverrides field was removed. It carried both @ManyToMany with a
 * @JoinTable and @ElementCollection with a @CollectionTable on the same field,
 * which is an invalid mapping and prevented the application from starting.
 *
 * refreshToken and tokenExpiry were also removed. A single token column on the
 * user row means one session per account, so revoking a lost device would sign
 * the user out everywhere. A UserSession table replaces it in the identity
 * milestone.
 *
 * The role field stays as an enum for now. It is replaced by Role and
 * Permission entities in the identity milestone so the permission matrix lives
 * as data rather than in code.
 */
@Entity
@Table(name = "users", uniqueConstraints = {
        @UniqueConstraint(name = "uk_users_email", columnNames = "email"),
        @UniqueConstraint(name = "uk_users_username", columnNames = "username")
})
@Getter
@Setter
public class Users extends SoftDeletableEntity implements UserDetails {

    @Column(name = "username", nullable = false, length = 100)
    private String username;

    @Column(name = "email", nullable = false, length = 100)
    private String email;

    /**
     * Set when the recipient follows the invitation link, which proves the
     * address reaches them. A password reset is never sent to an unverified
     * address: a typo made at account creation would otherwise hand the
     * account to whoever owns that address.
     */
    @Column(name = "email_verified_at")
    private LocalDateTime emailVerifiedAt;

    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "first_name", nullable = false, length = 50)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 50)
    private String lastName;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "staff_number", length = 50)
    private String staffNumber;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /**
     * Finer-grained than isActive. INVITED means the account exists and an
     * invitation has been sent, but no password is set and it cannot
     * authenticate. Separating it from DEACTIVATED lets an administrator see
     * who has not taken up their invitation.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "mfa_enabled", nullable = false)
    private Boolean mfaEnabled = false;

    @Column(name = "must_change_password", nullable = false)
    private Boolean mustChangePassword = false;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "failed_login_attempts", nullable = false)
    private Integer failedLoginAttempts = 0;

    @Column(name = "account_locked", nullable = false)
    private Boolean accountLocked = false;

    @Column(name = "lock_expiry")
    private LocalDateTime lockExpiry;

    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    @Column(name = "last_password_reset_at")
    private LocalDateTime lastPasswordResetAt;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "invited_at")
    private LocalDateTime invitedAt;

    @Column(name = "invited_by", length = 100)
    private String invitedBy;

    @Column(name = "deactivated_at")
    private LocalDateTime deactivatedAt;

    @Column(name = "deactivated_reason", length = 500)
    private String deactivatedReason;

    /** Null for FNPH staff and for patients. Present for centre staff only. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id", foreignKey = @ForeignKey(name = "fk_users_centre"))
    private Center centre;

    /** Null for staff. Present for patient accounts only. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", foreignKey = @ForeignKey(name = "fk_users_patient"))
    private Patient patient;

    @Column(name = "login_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private LoginType loginType = LoginType.USERNAME;

    /**
     * Roles held by this account. Replaces the single role column removed in
     * V5. A user may hold more than one; exactly one is marked primary and
     * decides where authentication lands them.
     */
    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    private Set<UserRole> roleAssignments = new HashSet<>();

    /**
     * Not the authorisation source. Authentication builds a
     * {@link com.fnph.telepsychiatric.security.SecurityUser} snapshot with the
     * full role and permission set; this entity is never the principal. The
     * method is present only because UserDetails requires it.
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        if (Boolean.FALSE.equals(accountLocked)) {
            return true;
        }
        return lockExpiry != null && lockExpiry.isBefore(LocalDateTime.now());
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return Boolean.TRUE.equals(isActive)
                && Boolean.FALSE.equals(getDeleted())
                && status == UserStatus.ACTIVE;
    }

    public String getFullName() {
        return firstName + " " + lastName;
    }

    /** True when this account is bound to a Centre of Excellence tenant. */
    public boolean isCentreBound() {
        return centre != null;
    }

    /** True when this account belongs to a patient rather than to staff. */
    public boolean isPatientAccount() {
        return patient != null;
    }
}
