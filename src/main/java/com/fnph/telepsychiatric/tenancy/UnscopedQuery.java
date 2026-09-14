package com.fnph.telepsychiatric.tenancy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a derived query on a tenant-owned entity that deliberately does not
 * name a centre.
 *
 * Required because the Hibernate filter in {@link TenantAwareRepository} does
 * not reach derived queries. A query without a centre predicate and without
 * this annotation is an omission, and TenantQueryScopingTest fails the build.
 *
 * Three legitimate reasons, and no others:
 *
 * {@link Reason#HOSPITAL_QUEUE} — an FNPH-staff queue across all centres. Safe
 * only while no centre role holds the permission that reaches it, which the
 * test asserts separately.
 *
 * {@link Reason#KEYED_BY_SCOPED_PARENT} — the argument is the identifier of a
 * row the caller has already resolved through a scoped path, so the check has
 * already happened upstream.
 *
 * {@link Reason#KEYED_BY_SECRET} — the argument is an unguessable credential,
 * such as a token hash, so possession is the authorisation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface UnscopedQuery {

    Reason value();

    /** Why, in a sentence. Read by whoever audits this next. */
    String detail();

    enum Reason {
        HOSPITAL_QUEUE,
        KEYED_BY_SCOPED_PARENT,
        KEYED_BY_SECRET
    }
}