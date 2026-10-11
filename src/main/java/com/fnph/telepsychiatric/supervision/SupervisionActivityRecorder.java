package com.fnph.telepsychiatric.supervision;

import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.audit.SupervisionContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records what an administrator looked at during a supervised session.
 *
 * Supervision is read-only, so the ordinary audit trail (written where records
 * change) would show nothing for a session. Each screen viewed is recorded here
 * instead, as VIEW_AS_ACTION with the method and path, so the per-session log
 * answers "what did they see".
 *
 * Screens poll. The same path in the same session is recorded at most once a
 * minute, so a dashboard left open does not write a row every 30 seconds.
 * Query strings are never recorded: they can carry search terms such as a
 * patient's name.
 */
@Component
@RequiredArgsConstructor
public class SupervisionActivityRecorder {

    static final long REPEAT_WINDOW_MS = 60_000;
    private static final int PRUNE_ABOVE = 5_000;

    private final ViewAsSessionRepository viewAsRepository;
    private final AuditService auditService;

    private final Map<String, Long> lastRecorded = new ConcurrentHashMap<>();

    /** A read made inside the session. Requires the supervision context to be set. */
    public void viewed(SupervisionContext.Snapshot session, String method, String path) {
        long now = System.currentTimeMillis();
        String key = session.viewAsSessionId() + " " + method + " " + path;
        Long previous = lastRecorded.put(key, now);
        if (previous != null && now - previous < REPEAT_WINDOW_MS) {
            return;
        }
        if (lastRecorded.size() > PRUNE_ABOVE) {
            lastRecorded.entrySet().removeIf(e -> now - e.getValue() > REPEAT_WINDOW_MS);
        }

        viewAsRepository.incrementActions(session.viewAsSessionId());
        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.VIEW_AS_ACTION)
                .entityType("ViewAsSession")
                .entityId(session.viewAsSessionId())
                .details(method + " " + path)
                .build());
    }

    /** A change attempted inside the session, refused because supervision is read-only. */
    public void refused(SupervisionContext.Snapshot session, String method, String path) {
        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.VIEW_AS_ACTION)
                .entityType("ViewAsSession")
                .entityId(session.viewAsSessionId())
                .details("Refused " + method + " " + path + ": supervised access is read-only")
                .outcome("DENIED")
                .build());
    }
}
