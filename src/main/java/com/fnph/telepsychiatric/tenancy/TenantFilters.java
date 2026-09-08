package com.fnph.telepsychiatric.tenancy;

/**
 * Names shared between the {@code @FilterDef} declarations and the code that
 * enables them. String literals in two places drift; these do not.
 */
public final class TenantFilters {

    /** Constrains a query to one centre. Enabled only for centre principals. */
    public static final String CENTRE_TENANT = "centreTenantFilter";

    public static final String CENTRE_ID_PARAM = "centreId";

    /**
     * The SQL fragment appended to every tenant-owned query.
     *
     * A centre principal with no centre id resolves this to
     * {@code centre_id = -1}, which matches nothing. Failing closed matters
     * more here than a helpful error: a bug that leaves the context unset must
     * return no rows, not every centre's rows.
     */
    public static final String CONDITION = "centre_id = :centreId";

    /** Sentinel used when a centre principal somehow has no centre bound. */
    public static final long NO_CENTRE = -1L;

    private TenantFilters() {
    }
}
