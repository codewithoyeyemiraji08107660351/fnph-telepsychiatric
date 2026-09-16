package com.fnph.telepsychiatric.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fnph.telepsychiatric.session.SessionService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Registered as @Component rather than @Service. It is a filter, not a service,
 * and the previous annotation made it eligible for component scanning in the
 * wrong layer.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final SessionService sessionService;

    /** The one endpoint under /auth that acts on the signed-in account, so it needs the token read. */
    static final String PASSWORD_CHANGE_PATH = "/api/v1/auth/password/change";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (PASSWORD_CHANGE_PATH.equals(path)) {
            return false;
        }
        return path.startsWith("/api/v1/auth/")
                || path.startsWith("/api/v1/enrolment/")
                || path.startsWith("/api/v1/webhooks/")
                || path.startsWith("/api/v1/verify/")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        final String token = authHeader.substring(7);
        try {
            Optional<JwtService.AccessToken> parsed = jwtService.parseAccessToken(token);
            if (parsed.isPresent() && SecurityContextHolder.getContext().getAuthentication() == null) {
                authenticate(parsed.get(), request);
            }
        } catch (Exception ex) {
            // A bad token is an unauthenticated request, not a 500. Logged at
            // WARN with the exception, because a failure here answers 401 with
            // an empty body and nothing else says why.
            SecurityContextHolder.clearContext();
            log.warn("Refused bearer token on {} {}: {} ({})", request.getMethod(),
                    request.getServletPath(), ex.getClass().getSimpleName(), ex.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    /**
     * The session is checked before the account is loaded: it is the cheaper
     * query and the more likely reason to refuse. A token with no session
     * claim predates session binding and is refused; the client's normal
     * refresh then returns a bound token, so nobody is signed out by the
     * deployment.
     */
    private void authenticate(JwtService.AccessToken access, HttpServletRequest request) {
        String where = request.getMethod() + " " + request.getServletPath();
        if (access.sessionPublicId() == null || access.userId() == null || access.username() == null) {
            log.warn("Refused bearer token on {}: no session binding (issued before session-bound tokens)", where);
            return;
        }
        Optional<Long> sessionId = sessionService.authenticateRequest(access.sessionPublicId(), access.userId());
        if (sessionId.isEmpty()) {
            // SessionService logs which check failed.
            log.warn("Refused bearer token on {}: session {} for user {} is not usable",
                    where, access.sessionPublicId(), access.userId());
            return;
        }

        UserDetails userDetails = userDetailsService.loadUserByUsername(access.username());
        if (!userDetails.isEnabled() || !userDetails.isAccountNonLocked()) {
            log.warn("Refused bearer token on {}: account {} is disabled or locked", where, access.userId());
            return;
        }
        // The username in the token must still belong to the account the session was opened for.
        if (userDetails instanceof SecurityUser securityUser
                && !access.userId().equals(securityUser.getUserId())) {
            log.warn("Refused bearer token on {}: username no longer belongs to user {}", where, access.userId());
            return;
        }

        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authToken);
        CurrentSession.bind(request, sessionId.get(), access.sessionPublicId());
    }
}
