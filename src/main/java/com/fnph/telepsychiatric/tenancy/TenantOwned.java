package com.fnph.telepsychiatric.tenancy;

/**
 * Marks an entity that belongs to a Centre of Excellence.
 *
 * Implementing this is what puts an entity under tenant enforcement. Two things
 * follow from it: the Hibernate filter applies to every query, and
 * {@code findById} results are verified against the caller's centre.
 *
 * @return the owning centre id, or null when the row belongs to the FNPH
 *         pathway. The dual-owned clinical tables (prescriptions,
 *         investigations, follow-ups) return null for an FNPH row and a centre
 *         id for a centre one.
 */
public interface TenantOwned {

    Long resolveCentreId();
}
