package com.fnph.telepsychiatric.tenancy;

import com.fnph.telepsychiatric.authz.RoleScope;
import com.fnph.telepsychiatric.security.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Sets the tenant scope for the request from the authenticated principal.
 *
 * Ordered to run after Spring Security's filter chain, because it needs the
 * principal that authentication produced. An unauthenticated request keeps the
 * default scope, which is constrained with no centre and therefore matches
 * nothing.
 *
 * The clear() in the finally block is not optional. Servlet containers reuse
 * threads, so a scope left behind would be inherited by whichever request the
 * pool hands that thread to next, and the symptom would be one centre
 * intermittently seeing another's data under load. That is the worst class of
 * bug in this system: rare, load-dependent, and a disclosure every time.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
@Slf4j
public class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        try {
            TenantContext.set(resolveScope());
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private TenantScope resolveScope() {
        return CurrentUser.get()
                .map(user -> {
                    if (user.getScope() == RoleScope.CENTRE) {
                        if (user.getCentreId() == null) {
                            // Should be impossible: role assignment refuses a
                            // centre role on an account with no centre. If it
                            // happens anyway, deny rather than widen.
                            log.error("Centre principal {} has no centre bound. Denying all tenant data.",
                                    user.getUsername());
                            return TenantScope.centre(null);
                        }
                        return TenantScope.centre(user.getCentreId());
                    }
                    if (user.getScope() == RoleScope.FNPH) {
                        return TenantScope.hospital(
                                "FNPH staff: " + user.getPrimaryRole());
                    }
                    // A patient has no business in centre data at all. Leaving
                    // them constrained with no centre means every tenant-owned
                    // query returns nothing, on top of the permission checks.
                    return TenantScope.none();
                })
                .orElseGet(TenantScope::none);
    }
}
