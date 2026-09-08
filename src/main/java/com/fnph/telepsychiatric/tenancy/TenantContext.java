package com.fnph.telepsychiatric.tenancy;

/**
 * The tenant scope for the current thread.
 *
 * Set once per request from the authenticated principal, read by the repository
 * layer, cleared in a finally block. A ThreadLocal rather than a parameter
 * because the alternative is threading a centre id through every method
 * signature in the application, and the one place somebody forgets is the leak.
 *
 * Fails closed. An unset context is {@link TenantScope#none()}, which is
 * constrained with a null centre id, so a tenant-owned query returns nothing
 * rather than everything.
 */
public final class TenantContext {

    private static final ThreadLocal<TenantScope> CURRENT =
            ThreadLocal.withInitial(TenantScope::none);

    private TenantContext() {
    }

    public static TenantScope current() {
        return CURRENT.get();
    }

    public static void set(TenantScope scope) {
        CURRENT.set(scope);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Runs a block with hospital-wide scope.
     *
     * For scheduled jobs and webhook handlers that legitimately span centres:
     * the nightly no-show sweep, wallet threshold alerts, Remita reconciliation.
     * The reason is mandatory and appears in the audit trail, so a widened scope
     * always has a stated justification attached to it.
     */
    public static <T> T runAcrossAllCentres(String reason, java.util.function.Supplier<T> work) {
        TenantScope previous = CURRENT.get();
        try {
            CURRENT.set(TenantScope.hospital(reason));
            return work.get();
        } finally {
            CURRENT.set(previous);
        }
    }

    public static void runAcrossAllCentres(String reason, Runnable work) {
        runAcrossAllCentres(reason, () -> {
            work.run();
            return null;
        });
    }
}
