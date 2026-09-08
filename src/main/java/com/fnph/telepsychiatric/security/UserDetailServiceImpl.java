package com.fnph.telepsychiatric.security;

import com.fnph.telepsychiatric.authz.Role;
import com.fnph.telepsychiatric.authz.RoleScope;
import com.fnph.telepsychiatric.authz.UserRole;
import com.fnph.telepsychiatric.authz.UserRoleRepository;
import com.fnph.telepsychiatric.user.UserRepository;
import com.fnph.telepsychiatric.user.Users;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the {@link SecurityUser} snapshot at authentication.
 *
 * Resolution order is username then email. At activation a patient's EHR
 * number is written into the username column, so all three login types
 * converge on one lookup rather than each needing its own branch.
 */
@Service
@RequiredArgsConstructor
public class UserDetailServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
        Users user = userRepository.findByUsernameIgnoreCase(identifier)
                .or(() -> userRepository.findByEmailIgnoreCase(identifier))
                // Deliberately the same message either way. A distinct "no such
                // user" reply turns the sign-in form into a way to discover
                // which EHR numbers hold accounts.
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));

        List<UserRole> assignments = userRoleRepository.findAllByUserId(user.getId());
        if (assignments.isEmpty()) {
            // An account with no role can authenticate but do nothing, which is
            // confusing to diagnose. Refuse it at the door.
            throw new UsernameNotFoundException("Invalid credentials");
        }

        UserRole primary = assignments.stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsPrimary()))
                .findFirst()
                .orElse(assignments.get(0));

        Role primaryRole = primary.getRole();

        Set<String> roles = new LinkedHashSet<>(
                userRoleRepository.findRoleCodesByUserId(user.getId()));
        Set<String> permissions = new LinkedHashSet<>(
                userRoleRepository.findPermissionCodesByUserId(user.getId()));

        return SecurityUser.builder()
                .userId(user.getId())
                .publicId(user.getPublicId())
                .username(user.getUsername())
                .password(user.getPassword())
                .displayName(user.getFullName())
                .active(Boolean.TRUE.equals(user.getIsActive())
                        && Boolean.FALSE.equals(user.getDeleted()))
                .locked(!user.isAccountNonLocked())
                .mfaEnabled(Boolean.TRUE.equals(user.getMfaEnabled()))
                .mustChangePassword(Boolean.TRUE.equals(user.getMustChangePassword()))
                .centreId(user.getCentre() == null ? null : user.getCentre().getId())
                .patientId(user.getPatient() == null ? null : user.getPatient().getId())
                .scope(primaryRole == null ? RoleScope.FNPH : primaryRole.getScope())
                .primaryRole(primaryRole == null ? null : primaryRole.getCode())
                .dashboardRoute(primaryRole == null ? "/" : primaryRole.getDashboardRoute())
                .roles(roles)
                .permissions(permissions)
                .build();
    }
}
