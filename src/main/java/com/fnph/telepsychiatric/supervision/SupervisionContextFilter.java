package com.fnph.telepsychiatric.supervision;

import com.fnph.telepsychiatric.audit.SupervisionContext;
import com.fnph.telepsychiatric.security.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * Puts an open supervised session into scope for the request.
 *
 * The client sends the session identifier in a header while the administrator
 * is viewing another dashboard. The server looks it up rather than trusting the
 * header for anything beyond identifying which session: the session must exist,
 * belong to this administrator, and still be open.
 *
 * Cleared in a finally block, same reason as the tenant context. Servlet
 * threads are reused, and a supervision context left behind would attribute the
 * next request's actions to a session that had already ended.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 99)
@RequiredArgsConstructor
@Slf4j
public class SupervisionContextFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-View-As-Session";

    private final ViewAsSessionRepository viewAsRepository;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        try {
            resolve(request);
            chain.doFilter(request, response);
        } finally {
            SupervisionContext.clear();
        }
    }

    private void resolve(HttpServletRequest request) {
        String sessionPublicId = request.getHeader(HEADER);
        if (sessionPublicId == null || sessionPublicId.isBlank()) {
            return;
        }

        CurrentUser.get().ifPresent(principal ->
                viewAsRepository.findByPublicId(sessionPublicId).ifPresent(session -> {
                    boolean ownedByCaller =
                            session.getAdministrator().getId().equals(principal.getUserId());
                    boolean stillOpen = session.isOpen(LocalDateTime.now());

                    if (ownedByCaller && stillOpen) {
                        SupervisionContext.set(new SupervisionContext.Snapshot(
                                session.getId(),
                                session.getTargetUser().getId(),
                                session.getTargetRole().getCode()));
                        viewAsRepository.incrementActions(session.getId());
                    } else {
                        // A header naming someone else's session, or an expired
                        // one, is worth knowing about. The request continues
                        // with the administrator's own authority rather than
                        // failing, because the header is a view hint, not a
                        // grant of anything.
                        log.warn("Ignoring supervision header {}: ownedByCaller={} stillOpen={}",
                                sessionPublicId, ownedByCaller, stillOpen);
                    }
                }));
    }
}
