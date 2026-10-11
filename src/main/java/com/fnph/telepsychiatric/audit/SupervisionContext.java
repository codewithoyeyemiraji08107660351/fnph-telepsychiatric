package com.fnph.telepsychiatric.audit;

/**
 * The supervised session in effect on this thread, if any.
 *
 * Lives in the audit package rather than in supervision because the audit
 * writer is its main consumer, and putting it here avoids a dependency running
 * the wrong way.
 *
 * Cleared in a finally block by the request filter for the same reason the
 * tenant context is: servlet threads are reused, and a supervision context left
 * behind would attribute the next request's actions to a session that ended.
 */
public final class SupervisionContext {

    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

    private SupervisionContext() {
    }

    public static Snapshot current() {
        return CURRENT.get();
    }

    public static void set(Snapshot snapshot) {
        CURRENT.set(snapshot);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static boolean isActive() {
        return CURRENT.get() != null;
    }

    /**
     * @param viewAsSessionId       the supervised session row
     * @param targetUserId          the account being viewed
     * @param targetRoleCode        the role whose dashboard is open
     * @param administratorUserId   the administrator who authenticated
     * @param administratorUsername their username, for the audit row
     */
    public record Snapshot(Long viewAsSessionId, Long targetUserId, String targetRoleCode,
                           Long administratorUserId, String administratorUsername) {

        public Snapshot(Long viewAsSessionId, Long targetUserId, String targetRoleCode) {
            this(viewAsSessionId, targetUserId, targetRoleCode, null, null);
        }
    }
}
