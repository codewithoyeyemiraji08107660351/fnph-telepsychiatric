package com.fnph.telepsychiatric.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * The previous version returned HTTP 200 for any unauthenticated request whose
 * path began with /api/v1/auth/. Those paths are permitAll, so the branch was
 * unreachable, but a handler that can answer 200 to an authentication failure
 * is not something to leave in a clinical codebase. Removed.
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("""
        {
            "status": 401,
            "error": "Unauthorized",
            "message": "Authentication is required to access this resource"
        }
        """);
    }
}
