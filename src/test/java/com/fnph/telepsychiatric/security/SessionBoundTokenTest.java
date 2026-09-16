package com.fnph.telepsychiatric.security;

import com.fnph.telepsychiatric.authz.RoleScope;
import com.fnph.telepsychiatric.session.SessionService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Access tokens are bound to the session they were issued for, so revoking a
 * device ends its access on the next request instead of up to fifteen
 * minutes later.
 *
 * Drives the real JwtService and JwtAuthFilter with a mocked session store.
 * No Spring context.
 */
class SessionBoundTokenTest {

    private static final String SESSION = "01SESSIONPUBLICID00000000";

    private JwtService jwtService;
    private SessionService sessionService;
    private UserDetailsService userDetailsService;
    private JwtAuthFilter filter;
    private SecurityUser user;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecretKey(Base64.getEncoder().encodeToString(
                "test-signing-key-that-is-at-least-32-bytes-long".getBytes()));
        jwtService = new JwtService(properties);
        sessionService = mock(SessionService.class);
        userDetailsService = mock(UserDetailsService.class);
        filter = new JwtAuthFilter(jwtService, userDetailsService, sessionService);

        user = SecurityUser.builder()
                .userId(7L)
                .publicId("01TESTPRINCIPAL0000000000")
                .username("dr.bello")
                .password("irrelevant")
                .displayName("Aisha Bello")
                .active(true)
                .scope(RoleScope.FNPH)
                .primaryRole("DOCTOR")
                .roles(Set.of("DOCTOR"))
                .permissions(Set.of())
                .build();
        when(userDetailsService.loadUserByUsername("dr.bello")).thenReturn(user);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private Authentication run(String token, AtomicReference<Optional<String>> sessionSeen) throws Exception {
        return run("GET", "/api/v1/me", token, sessionSeen);
    }

    private Authentication run(String method, String path, String token,
                               AtomicReference<Optional<String>> sessionSeen) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        AtomicReference<Authentication> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            seen.set(SecurityContextHolder.getContext().getAuthentication());
            if (sessionSeen != null) {
                sessionSeen.set(CurrentSession.publicId());
            }
        };
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return seen.get();
    }

    @Test
    @DisplayName("the access token carries the session it was issued for")
    void tokenCarriesSession() {
        String token = jwtService.generateAccessToken(user, SESSION);
        JwtService.AccessToken parsed = jwtService.parseAccessToken(token).orElseThrow();
        assertThat(parsed.sessionPublicId()).isEqualTo(SESSION);
        assertThat(parsed.userId()).isEqualTo(7L);
        assertThat(parsed.username()).isEqualTo("dr.bello");
    }

    @Test
    @DisplayName("a challenge token is never accepted as an access token")
    void challengeTokenIsNotAccess() {
        String challenge = jwtService.generateChallengeToken(user, 5);
        assertThat(jwtService.parseAccessToken(challenge)).isEmpty();
    }

    @Test
    @DisplayName("a live session authenticates and exposes itself to the request")
    void liveSessionAuthenticates() throws Exception {
        when(sessionService.authenticateRequest(SESSION, 7L)).thenReturn(Optional.of(10L));
        AtomicReference<Optional<String>> current = new AtomicReference<>();

        Authentication auth = run(jwtService.generateAccessToken(user, SESSION), current);

        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isSameAs(user);
        assertThat(current.get()).contains(SESSION);
    }

    @Test
    @DisplayName("a revoked session is refused before the account is even loaded")
    void revokedSessionRefused() throws Exception {
        when(sessionService.authenticateRequest(SESSION, 7L)).thenReturn(Optional.empty());

        Authentication auth = run(jwtService.generateAccessToken(user, SESSION), null);

        assertThat(auth).isNull();
        verify(userDetailsService, never()).loadUserByUsername(anyString());
    }

    @Test
    @DisplayName("a deactivated account is refused even with a live session")
    void disabledAccountRefused() throws Exception {
        SecurityUser disabled = SecurityUser.builder()
                .userId(7L).publicId("01TESTPRINCIPAL0000000000").username("dr.bello")
                .password("irrelevant").displayName("Aisha Bello").active(false)
                .scope(RoleScope.FNPH).primaryRole("DOCTOR")
                .roles(Set.of("DOCTOR")).permissions(Set.of()).build();
        when(userDetailsService.loadUserByUsername("dr.bello")).thenReturn(disabled);
        when(sessionService.authenticateRequest(SESSION, 7L)).thenReturn(Optional.of(10L));

        assertThat(run(jwtService.generateAccessToken(user, SESSION), null)).isNull();
    }

    @Test
    @DisplayName("a token whose username now belongs to a different account is refused")
    void reassignedUsernameRefused() throws Exception {
        SecurityUser other = SecurityUser.builder()
                .userId(99L).publicId("01OTHERPRINCIPAL000000000").username("dr.bello")
                .password("irrelevant").displayName("Someone Else").active(true)
                .scope(RoleScope.FNPH).primaryRole("DOCTOR")
                .roles(Set.of("DOCTOR")).permissions(Set.of()).build();
        when(userDetailsService.loadUserByUsername("dr.bello")).thenReturn(other);
        when(sessionService.authenticateRequest(SESSION, 7L)).thenReturn(Optional.of(10L));

        assertThat(run(jwtService.generateAccessToken(user, SESSION), null)).isNull();
    }

    @Test
    @DisplayName("garbage in the header is an anonymous request, not an error")
    void garbageIsAnonymous() throws Exception {
        assertThat(run("not-a-jwt", null)).isNull();
        verify(sessionService, never()).authenticateRequest(any(), any());
    }

    @Test
    @DisplayName("password change reads the token, so it knows which device to keep signed in")
    void passwordChangeIsAuthenticated() throws Exception {
        when(sessionService.authenticateRequest(SESSION, 7L)).thenReturn(Optional.of(10L));
        AtomicReference<Optional<String>> current = new AtomicReference<>();

        Authentication auth = run("POST", JwtAuthFilter.PASSWORD_CHANGE_PATH,
                jwtService.generateAccessToken(user, SESSION), current);

        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isSameAs(user);
        assertThat(current.get()).contains(SESSION);
    }

    @Test
    @DisplayName("the public sign-in endpoints still skip token processing")
    void publicAuthEndpointsSkipTheFilter() throws Exception {
        Authentication auth = run("POST", "/api/v1/auth/login",
                jwtService.generateAccessToken(user, SESSION), null);

        assertThat(auth).isNull();
        verify(sessionService, never()).authenticateRequest(any(), any());
    }
}
