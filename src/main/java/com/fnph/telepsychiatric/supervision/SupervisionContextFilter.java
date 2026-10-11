package com.fnph.telepsychiatric.supervision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fnph.telepsychiatric.audit.SupervisionContext;
import com.fnph.telepsychiatric.authz.Permissions;
import com.fnph.telepsychiatric.authz.UserRoleRepository;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.security.SecurityUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Runs a supervised request as the account being viewed, read-only.
 *
 * <h2>What happens on a request carrying {@value #HEADER}</h2>
 *
 * The header only names a session. The server looks it up and uses it only if
 * the session exists, belongs to the caller, is still open, and the caller
 * still holds {@code supervision.view_as}. Otherwise the request continues with
 * the administrator's own authority, exactly as if the header were absent.
 *
 * For a usable session:
 * <ol>
 *   <li><b>Changes are refused outright.</b> Anything other than GET, HEAD or
 *       OPTIONS answers 403 and is recorded. This does not depend on how a
 *       permission is classified, so a permission added later cannot open a
 *       write path.</li>
 *   <li><b>The request runs as the viewed account,</b> holding only that
 *       account's non-mutating permissions. Screens that show "my" data (a
 *       doctor's queue, a nurse's work history) show theirs, and
 *       {@code @PreAuthorize} refuses anything else. The administrator's own
 *       permissions do not apply inside the session.</li>
 *   <li><b>Both identities are kept.</b> The administrator is held in
 *       {@link SupervisionContext}, and the audit writer names them as the user
 *       and the viewed account as the effective principal.</li>
 *   <li><b>What was viewed is recorded</b> by {@link SupervisionActivityRecorder}.</li>
 * </ol>
 *
 * The administrator's own session endpoints (sign-in, /me, sessions,
 * notifications, and supervision itself) are never supervised: ending the
 * session, signing out or reading the administrator's own profile must act on
 * the administrator.
 *
 * <h2>Order</h2>
 *
 * After Spring Security (which authenticates the administrator) and before
 * {@code TenantContextFilter}, so tenant scoping follows the viewed account:
 * supervising a centre user scopes queries to that centre.
 *
 * Everything set here is undone in a finally block. Servlet threads are reused,
 * and a supervised identity left behind would be inherited by the next request.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 101)
@RequiredArgsConstructor
@Slf4j
public class SupervisionContextFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-View-As-Session";

    static final String READ_ONLY_MESSAGE =
            "Supervised access is read-only. Nothing can be changed on another user's behalf. "
                    + "End the supervised session to act as yourself.";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /** The administrator's own account and session. Never run as someone else. */
    private static final List<String> OWN_PREFIXES = List.of(
            "/api/v1/auth/",
            "/api/v1/admin/supervision",
            "/api/v1/sessions",
            "/api/v1/notifications");
    private static final Set<String> OWN_EXACT = Set.of(
            "/api/v1/me",
            "/api/v1/me/profile");

    private final ViewAsSessionRepository viewAsRepository;
    private final UserDetailsService userDetailsService;
    private final UserRoleRepository userRoleRepository;
    private final SupervisionActivityRecorder recorder;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        SecurityContext original = SecurityContextHolder.getContext();
        try {
            Optional<Supervised> supervised = resolve(request);
            if (supervised.isPresent()) {
                Supervised s = supervised.get();
                SupervisionContext.set(s.snapshot());
                String method = request.getMethod();
                String path = request.getRequestURI();

                if (!SAFE_METHODS.contains(method)) {
                    recorder.refused(s.snapshot(), method, path);
                    refuse(response, path);
                    return;
                }

                SecurityContext asTarget = SecurityContextHolder.createEmptyContext();
                UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                        s.readOnlyTarget(), null, s.readOnlyTarget().getAuthorities());
                Authentication admin = original.getAuthentication();
                if (admin != null) {
                    token.setDetails(admin.getDetails());
                }
                asTarget.setAuthentication(token);
                SecurityContextHolder.setContext(asTarget);

                if (!"OPTIONS".equals(method)) {
                    recorder.viewed(s.snapshot(), method, path);
                }
            }
            chain.doFilter(request, response);
        } finally {
            SupervisionContext.clear();
            SecurityContextHolder.setContext(original);
        }
    }

    /** The open session this request belongs to, with the viewed account ready to run as. */
    private Optional<Supervised> resolve(HttpServletRequest request) {
        String sessionPublicId = request.getHeader(HEADER);
        if (sessionPublicId == null || sessionPublicId.isBlank() || isOwnAccountPath(request.getRequestURI())) {
            return Optional.empty();
        }
        Optional<SecurityUser> caller = CurrentUser.get();
        if (caller.isEmpty()) {
            return Optional.empty();
        }
        SecurityUser administrator = caller.get();

        Optional<ViewAsSession> found = viewAsRepository.findWithPartiesByPublicId(sessionPublicId);
        if (found.isEmpty()) {
            log.warn("Ignoring supervision header {}: no such session", sessionPublicId);
            return Optional.empty();
        }
        ViewAsSession session = found.get();

        boolean ownedByCaller = session.getAdministrator().getId().equals(administrator.getUserId());
        boolean stillOpen = session.isOpen(LocalDateTime.now());
        boolean stillPermitted = administrator.hasPermission(Permissions.SUPERVISION_VIEW_AS);
        if (!ownedByCaller || !stillOpen || !stillPermitted) {
            // The header is a pointer, not a grant. A header naming someone
            // else's session, an expired one, or one whose administrator has
            // since lost the permission changes nothing.
            log.warn("Ignoring supervision header {}: ownedByCaller={} stillOpen={} stillPermitted={}",
                    sessionPublicId, ownedByCaller, stillOpen, stillPermitted);
            return Optional.empty();
        }

        SecurityUser target;
        try {
            UserDetails details = userDetailsService.loadUserByUsername(session.getTargetUser().getUsername());
            if (!(details instanceof SecurityUser loaded) || !loaded.isEnabled() || !loaded.isAccountNonLocked()) {
                log.warn("Ignoring supervision header {}: the viewed account is no longer usable", sessionPublicId);
                return Optional.empty();
            }
            target = loaded;
        } catch (UsernameNotFoundException e) {
            log.warn("Ignoring supervision header {}: the viewed account has no active role", sessionPublicId);
            return Optional.empty();
        }

        Set<String> readOnly = new LinkedHashSet<>(
                userRoleRepository.findNonMutatingPermissionCodesByUserId(target.getUserId()));

        return Optional.of(new Supervised(
                new SupervisionContext.Snapshot(
                        session.getId(),
                        target.getUserId(),
                        session.getTargetRole().getCode(),
                        administrator.getUserId(),
                        administrator.getUsername()),
                readOnlyCopy(target, readOnly)));
    }

    static boolean isOwnAccountPath(String path) {
        if (path == null) {
            return false;
        }
        if (OWN_EXACT.contains(path)) {
            return true;
        }
        return OWN_PREFIXES.stream().anyMatch(path::startsWith);
    }

    /** The viewed account as it is, but holding its read permissions only. */
    static SecurityUser readOnlyCopy(SecurityUser target, Set<String> readOnlyPermissions) {
        Set<String> permissions = new LinkedHashSet<>(readOnlyPermissions);
        permissions.retainAll(target.getPermissions());
        return SecurityUser.builder()
                .userId(target.getUserId())
                .publicId(target.getPublicId())
                .username(target.getUsername())
                // Never usable to authenticate, and never needed here.
                .password("")
                .displayName(target.getDisplayName())
                .active(target.isActive())
                .locked(target.isLocked())
                .mfaEnabled(target.isMfaEnabled())
                .mustChangePassword(false)
                .centreId(target.getCentreId())
                .patientId(target.getPatientId())
                .scope(target.getScope())
                .primaryRole(target.getPrimaryRole())
                .dashboardRoute(target.getDashboardRoute())
                .roles(new LinkedHashSet<>(target.getRoles()))
                .permissions(permissions)
                .build();
    }

    private void refuse(HttpServletResponse response, String path) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.FORBIDDEN.value());
        body.put("error", HttpStatus.FORBIDDEN.getReasonPhrase());
        body.put("message", READ_ONLY_MESSAGE);
        body.put("path", path);
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    record Supervised(SupervisionContext.Snapshot snapshot, SecurityUser readOnlyTarget) {
    }
}
