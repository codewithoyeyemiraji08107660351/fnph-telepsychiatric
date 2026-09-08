package com.fnph.telepsychiatric.tenancy;

/**
 * Who is asking, from the point of view of tenant isolation.
 *
 * @param centreId     the centre every query must be constrained to, or null
 * @param unrestricted true when this principal legitimately works across
 *                     centres, which is FNPH staff only
 * @param reason       why the scope is what it is, for the audit trail
 */
public record TenantScope(Long centreId, boolean unrestricted, String reason) {

    /** A centre account. Every query is constrained to this centre. */
    public static TenantScope centre(Long centreId) {
        return new TenantScope(centreId, false, "Centre-scoped principal");
    }

    /**
     * FNPH staff.
     *
     * Not a loophole. The Hub Coordinator approves bookings from every centre
     * and the doctor consults for every centre, so hospital staff genuinely
     * work across all of them. What they cannot do is reach another centre's
     * data through a centre account, which is what the filter prevents.
     */
    public static TenantScope hospital(String reason) {
        return new TenantScope(null, true, reason);
    }

    /**
     * No principal: a scheduled job, a webhook handler, the public document
     * verification endpoint.
     *
     * Deliberately restricted rather than unrestricted. Code with no
     * authenticated caller should not silently gain access to every centre,
     * and a job that genuinely needs it says so explicitly.
     */
    public static TenantScope none() {
        return new TenantScope(null, false, "No authenticated principal");
    }

    /** True when a filter must be applied to tenant-owned queries. */
    public boolean isConstrained() {
        return !unrestricted;
    }
}
