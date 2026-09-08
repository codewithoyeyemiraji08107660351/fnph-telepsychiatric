package com.fnph.telepsychiatric.authz;

import com.fnph.telepsychiatric.authz.api.*;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.user.UserRepository;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<RoleSummaryResponse> listRoles(RoleScope scope) {
        List<Role> roles = scope == null
                ? roleRepository.findAllByIsActiveTrueOrderByScopeAscNameAsc()
                : roleRepository.findAllByScopeAndIsActiveTrueOrderByNameAsc(scope);

        Map<String, Long> counts = roleRepository.countPermissionsPerRole().stream()
                .collect(Collectors.toMap(
                        RoleRepository.PermissionCountView::getCode,
                        RoleRepository.PermissionCountView::getTotal));

        return roles.stream()
                .map(r -> new RoleSummaryResponse(
                        r.getCode(), r.getName(), r.getDescription(),
                        r.getScope().name(), r.getDashboardRoute(),
                        Boolean.TRUE.equals(r.getIsSystem()),
                        Boolean.TRUE.equals(r.getIsActive()),
                        counts.getOrDefault(r.getCode(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public RoleDetailResponse getRole(String code) {
        Role role = roleRepository.findWithPermissionsByCode(code)
                .orElseThrow(() -> new EntityNotFoundException("No role with code " + code));

        Map<String, List<PermissionResponse>> byModule = role.getPermissions().stream()
                .sorted(Comparator.comparing(Permission::getModule).thenComparing(Permission::getCode))
                .map(p -> new PermissionResponse(p.getCode(), p.getModule(), p.getDescription()))
                .collect(Collectors.groupingBy(PermissionResponse::module,
                        LinkedHashMap::new, Collectors.toList()));

        return new RoleDetailResponse(
                role.getCode(), role.getName(), role.getDescription(),
                role.getScope().name(), role.getDashboardRoute(),
                role.getPermissions().size(), byModule);
    }

    @Transactional(readOnly = true)
    public Map<String, List<PermissionResponse>> listPermissions(String module) {
        List<Permission> permissions = module == null
                ? permissionRepository.findAllByOrderByModuleAscCodeAsc()
                : permissionRepository.findAllByModuleOrderByCodeAsc(module);

        return permissions.stream()
                .map(p -> new PermissionResponse(p.getCode(), p.getModule(), p.getDescription()))
                .collect(Collectors.groupingBy(PermissionResponse::module,
                        LinkedHashMap::new, Collectors.toList()));
    }

    @Transactional(readOnly = true)
    public UserRoleAssignmentResponse getUserRoles(String userPublicId) {
        Users user = requireUser(userPublicId);
        return buildAssignmentResponse(user);
    }

    /**
     * Replaces a user's roles wholesale.
     *
     * Three rules are enforced here rather than left to the caller:
     *
     *  1. The primary role must be one of the roles being granted. Otherwise the
     *     post-login redirect points somewhere the user cannot go.
     *
     *  2. Every role must share one security boundary. An account holding both
     *     a CENTRE and an FNPH role would be a hole straight through tenant
     *     isolation, because the centre filter would apply to some of its
     *     requests and not others.
     *
     *  3. A patient account cannot be granted a staff role and the reverse. The
     *     patient application must reject staff credentials, and that check is
     *     only meaningful if the two sets stay disjoint.
     */
    @Transactional
    public UserRoleAssignmentResponse assignRoles(String userPublicId, AssignRolesRequest request) {
        Users user = requireUser(userPublicId);

        List<Role> roles = roleRepository.findAllByCodeIn(request.roleCodes());
        if (roles.size() != new HashSet<>(request.roleCodes()).size()) {
            Set<String> found = roles.stream().map(Role::getCode).collect(Collectors.toSet());
            List<String> unknown = request.roleCodes().stream().filter(c -> !found.contains(c)).toList();
            throw new IllegalArgumentException("Unknown role codes: " + unknown);
        }

        if (roles.stream().noneMatch(r -> r.getCode().equals(request.primaryRoleCode()))) {
            throw new IllegalArgumentException(
                    "The primary role must be one of the roles being assigned");
        }

        Set<RoleScope> scopes = roles.stream().map(Role::getScope).collect(Collectors.toSet());
        if (scopes.size() > 1) {
            throw new IllegalArgumentException(
                    "All roles must share one security boundary, but got " + scopes
                            + ". An account spanning boundaries would bypass tenant isolation.");
        }

        RoleScope scope = scopes.iterator().next();
        if (scope == RoleScope.CENTRE && user.getCentre() == null) {
            throw new IllegalArgumentException(
                    "A centre role requires the account to be bound to a centre");
        }
        if (scope != RoleScope.CENTRE && user.getCentre() != null) {
            throw new IllegalArgumentException(
                    "This account is bound to a centre and cannot hold a non-centre role");
        }
        if (scope == RoleScope.PATIENT && user.getPatient() == null) {
            throw new IllegalArgumentException(
                    "The PATIENT role requires the account to be linked to a patient record");
        }
        if (scope != RoleScope.PATIENT && user.getPatient() != null) {
            throw new IllegalArgumentException(
                    "This account is linked to a patient record and cannot hold a staff role");
        }

        String actor = CurrentUser.usernameOrSystem();
        LocalDateTime now = LocalDateTime.now();

        // Delete then insert, flushed in between, because the single-primary
        // unique index would otherwise reject the new primary while the old one
        // is still present.
        userRoleRepository.deleteAllByUserId(user.getId());
        userRoleRepository.flush();

        List<UserRole> assignments = roles.stream().map(role -> {
            UserRole ur = new UserRole();
            ur.setUserId(user.getId());
            ur.setRoleId(role.getId());
            ur.setIsPrimary(role.getCode().equals(request.primaryRoleCode()));
            ur.setGrantedAt(now);
            ur.setGrantedBy(actor);
            ur.setGrantReason(request.reason());
            return ur;
        }).toList();

        userRoleRepository.saveAll(assignments);
        userRoleRepository.flush();

        log.info("Roles for user {} set to {} (primary {}) by {}: {}",
                user.getPublicId(), request.roleCodes(), request.primaryRoleCode(), actor, request.reason());

        return buildAssignmentResponse(user);
    }

    private Users requireUser(String publicId) {
        return userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new EntityNotFoundException("No user with id " + publicId));
    }

    private UserRoleAssignmentResponse buildAssignmentResponse(Users user) {
        List<UserRole> assignments = userRoleRepository.findAllByUserId(user.getId());

        List<UserRoleAssignmentResponse.HeldRole> held = assignments.stream()
                .sorted(Comparator.comparing((UserRole a) -> !Boolean.TRUE.equals(a.getIsPrimary())))
                .map(a -> new UserRoleAssignmentResponse.HeldRole(
                        a.getRole().getCode(), a.getRole().getName(),
                        Boolean.TRUE.equals(a.getIsPrimary()),
                        a.getGrantedAt(), a.getGrantedBy(), a.getGrantReason()))
                .toList();

        UserRole primary = assignments.stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsPrimary()))
                .findFirst().orElse(null);

        return new UserRoleAssignmentResponse(
                user.getPublicId(), user.getUsername(), user.getFullName(),
                primary == null ? null : primary.getRole().getCode(),
                primary == null ? null : primary.getRole().getDashboardRoute(),
                held,
                userRoleRepository.findPermissionCodesByUserId(user.getId()).stream().sorted().toList());
    }
}
