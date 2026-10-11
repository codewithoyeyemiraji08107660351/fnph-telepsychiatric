package com.fnph.telepsychiatric.supervision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fnph.telepsychiatric.audit.SupervisionContext;
import com.fnph.telepsychiatric.authz.Role;
import com.fnph.telepsychiatric.authz.RoleScope;
import com.fnph.telepsychiatric.authz.UserRoleRepository;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.security.SecurityUser;
import com.fnph.telepsychiatric.user.Users;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupervisionContextFilterTest {

    private static final String SESSION = "01SUPERVISIONSESSION000001";

    private final ViewAsSessionRepository repository = mock(ViewAsSessionRepository.class);
    private final UserDetailsService userDetailsService = mock(UserDetailsService.class);
    private final UserRoleRepository userRoleRepository = mock(UserRoleRepository.class);
    private final SupervisionActivityRecorder recorder = mock(SupervisionActivityRecorder.class);

    private final SupervisionContextFilter filter = new SupervisionContextFilter(
            repository, userDetailsService, userRoleRepository, recorder, new ObjectMapper());

    private final SecurityUser admin = principal(1L, "admin", RoleScope.FNPH, "CENTRAL_ADMINISTRATOR",
            Set.of("supervision.view_as", "appointment.approve", "config.update"));
    private final SecurityUser doctor = principal(2L, "dr.bello", RoleScope.FNPH, "DOCTOR",
            Set.of("consultation.read", "clinical_note.read", "clinical_note.write", "prescription.write"));

    /** What the request saw while it was inside the chain. */
    private final AtomicReference<SecurityUser> seenUser = new AtomicReference<>();
    private final AtomicReference<SupervisionContext.Snapshot> seenSupervision = new AtomicReference<>();
    private final FilterChain chain = (req, res) -> {
        seenUser.set(CurrentUser.get().orElse(null));
        seenSupervision.set(SupervisionContext.current());
    };

    @BeforeEach
    void signInAsAdministrator() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
        when(userDetailsService.loadUserByUsername("dr.bello")).thenReturn(doctor);
        when(userRoleRepository.findNonMutatingPermissionCodesByUserId(2L))
                .thenReturn(List.of("consultation.read", "clinical_note.read"));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        SupervisionContext.clear();
    }

    @Test
    @DisplayName("a supervised read runs as the viewed account with its read permissions only")
    void readRunsAsTargetReadOnly() throws Exception {
        sessionOpen(1L, LocalDateTime.now().plusMinutes(10));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("GET", "/api/v1/clinical/worklist"), response, chain);

        assertThat(seenUser.get().getUserId()).isEqualTo(2L);
        assertThat(seenUser.get().getPermissions())
                .containsExactlyInAnyOrder("consultation.read", "clinical_note.read")
                .doesNotContain("clinical_note.write", "appointment.approve", "supervision.view_as");
        assertThat(seenSupervision.get().administratorUserId()).isEqualTo(1L);
        assertThat(seenSupervision.get().targetUserId()).isEqualTo(2L);
        verify(recorder).viewed(any(), eq("GET"), eq("/api/v1/clinical/worklist"));

        // Nothing leaks to the next request on this thread.
        assertThat(CurrentUser.get().map(SecurityUser::getUserId)).contains(1L);
        assertThat(SupervisionContext.current()).isNull();
    }

    @Test
    @DisplayName("a change inside a supervised session is refused, whatever the permissions")
    void writeIsRefused() throws Exception {
        sessionOpen(1L, LocalDateTime.now().plusMinutes(10));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("POST", "/api/v1/hub/approvals/A1/approve"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("read-only");
        assertThat(seenUser.get()).isNull();
        verify(recorder).refused(any(), eq("POST"), eq("/api/v1/hub/approvals/A1/approve"));
        assertThat(SupervisionContext.current()).isNull();
    }

    @Test
    @DisplayName("a header naming someone else's session changes nothing")
    void foreignSessionIgnored() throws Exception {
        sessionOpen(7L, LocalDateTime.now().plusMinutes(10));

        filter.doFilter(request("POST", "/api/v1/admin/configuration/x"), new MockHttpServletResponse(), chain);

        assertThat(seenUser.get().getUserId()).isEqualTo(1L);
        assertThat(seenSupervision.get()).isNull();
        verify(recorder, never()).refused(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("an expired session changes nothing")
    void expiredSessionIgnored() throws Exception {
        sessionOpen(1L, LocalDateTime.now().minusMinutes(1));

        filter.doFilter(request("GET", "/api/v1/clinical/worklist"), new MockHttpServletResponse(), chain);

        assertThat(seenUser.get().getUserId()).isEqualTo(1L);
        assertThat(seenSupervision.get()).isNull();
    }

    @Test
    @DisplayName("an administrator who lost supervision.view_as is not supervised")
    void permissionRevokedIgnored() throws Exception {
        SecurityUser demoted = principal(1L, "admin", RoleScope.FNPH, "CENTRAL_ADMINISTRATOR",
                Set.of("appointment.approve"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(demoted, null, demoted.getAuthorities()));
        sessionOpen(1L, LocalDateTime.now().plusMinutes(10));

        filter.doFilter(request("GET", "/api/v1/clinical/worklist"), new MockHttpServletResponse(), chain);

        assertThat(seenUser.get().getUserId()).isEqualTo(1L);
        assertThat(seenSupervision.get()).isNull();
    }

    @Test
    @DisplayName("the administrator's own account endpoints are never supervised")
    void ownAccountPathsRunAsAdministrator() throws Exception {
        sessionOpen(1L, LocalDateTime.now().plusMinutes(10));

        filter.doFilter(request("GET", "/api/v1/me"), new MockHttpServletResponse(), chain);
        assertThat(seenUser.get().getUserId()).isEqualTo(1L);

        MockHttpServletResponse end = new MockHttpServletResponse();
        filter.doFilter(request("DELETE", "/api/v1/admin/supervision/" + SESSION), end, chain);
        assertThat(end.getStatus()).isEqualTo(200);
        assertThat(seenUser.get().getUserId()).isEqualTo(1L);
        assertThat(seenSupervision.get()).isNull();
    }

    @Test
    @DisplayName("the read-only copy never holds a permission the account does not have")
    void readOnlyCopyIsIntersection() {
        SecurityUser copy = SupervisionContextFilter.readOnlyCopy(doctor, Set.of("consultation.read", "audit.read"));
        assertThat(copy.getPermissions()).containsExactly("consultation.read");
        assertThat(copy.getPassword()).isEmpty();
    }

    @Test
    @DisplayName("runs after Spring Security and before tenant scoping")
    void orderedBetweenSecurityAndTenancy() {
        // After security, so the administrator is authenticated. Before the
        // tenant filter, so a supervised centre account is scoped to its centre
        // rather than to the administrator's hospital-wide view.
        int supervision = org.springframework.core.annotation.AnnotationUtils
                .findAnnotation(SupervisionContextFilter.class, org.springframework.core.annotation.Order.class).value();
        int tenancy = org.springframework.core.annotation.AnnotationUtils
                .findAnnotation(com.fnph.telepsychiatric.tenancy.TenantContextFilter.class,
                        org.springframework.core.annotation.Order.class).value();
        assertThat(supervision).isGreaterThan(org.springframework.core.Ordered.LOWEST_PRECEDENCE - 1000);
        assertThat(supervision).isLessThan(tenancy);
    }

    // ------------------------------------------------------------------

    private void sessionOpen(Long administratorId, LocalDateTime expiresAt) {
        Users administrator = new Users();
        administrator.setId(administratorId);
        Users target = new Users();
        target.setId(2L);
        target.setUsername("dr.bello");
        Role role = new Role();
        role.setCode("DOCTOR");

        ViewAsSession session = new ViewAsSession();
        session.setId(99L);
        session.setAdministrator(administrator);
        session.setTargetUser(target);
        session.setTargetRole(role);
        session.setStartedAt(LocalDateTime.now().minusMinutes(5));
        session.setExpiresAt(expiresAt);
        when(repository.findWithPartiesByPublicId(SESSION)).thenReturn(Optional.of(session));
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.addHeader(SupervisionContextFilter.HEADER, SESSION);
        return request;
    }

    private static SecurityUser principal(Long id, String username, RoleScope scope, String role, Set<String> permissions) {
        return SecurityUser.builder()
                .userId(id)
                .publicId("U" + id)
                .username(username)
                .password("x")
                .displayName(username)
                .active(true)
                .locked(false)
                .mfaEnabled(true)
                .mustChangePassword(false)
                .scope(scope)
                .primaryRole(role)
                .dashboardRoute("/")
                .roles(Set.of(role))
                .permissions(permissions)
                .build();
    }
}
