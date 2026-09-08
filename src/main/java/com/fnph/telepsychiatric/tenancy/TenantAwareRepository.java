package com.fnph.telepsychiatric.tenancy;

import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * The base class every Spring Data repository in this application inherits.
 *
 * Tenant isolation is enforced here rather than in services or controllers,
 * because those are written one at a time by people who each have to remember.
 * This is written once and applies to every repository that exists now or
 * later, including ones added after everyone has forgotten this was a concern.
 *
 * <h2>Two mechanisms, because one is not enough</h2>
 *
 * <b>1. The Hibernate filter</b> is enabled on the session before every query.
 * It appends {@code centre_id = :centreId} to JPQL, criteria and derived
 * queries alike, so a repository method written next year is covered without
 * its author knowing the filter exists.
 *
 * <b>2. An explicit check on {@code findById}</b>, because Hibernate filters
 * are documented not to apply to {@code EntityManager.find()}. That is exactly
 * the gap the acceptance criteria test: altering a record identifier in a URL
 * goes straight to a primary key lookup, which the filter would happily let
 * through. Without this second check the first one gives false confidence.
 *
 * The same gap exists for {@code getReferenceById} and for anything reached by
 * navigating an association from a permitted row, which is why the service
 * layer still validates before it follows a reference into another aggregate.
 */
@Slf4j
public class TenantAwareRepository<T, ID> extends SimpleJpaRepository<T, ID> {

    private final EntityManager entityManager;
    private final Class<T> domainType;
    private final boolean tenantOwned;

    public TenantAwareRepository(JpaEntityInformation<T, ?> entityInformation,
                                 EntityManager entityManager) {
        super(entityInformation, entityManager);
        this.entityManager = entityManager;
        this.domainType = entityInformation.getJavaType();
        this.tenantOwned = TenantOwned.class.isAssignableFrom(domainType);
    }

    /**
     * Turns the filter on for this session when the caller is centre-scoped.
     *
     * A centre principal with no centre bound resolves to the sentinel, which
     * matches nothing. Failing closed is the point: a bug that leaves the
     * context unset must return no rows rather than every centre's rows.
     */
    private void applyTenantFilter() {
        if (!tenantOwned) {
            return;
        }
        TenantScope scope = TenantContext.current();
        Session session = entityManager.unwrap(Session.class);

        if (scope.isConstrained()) {
            long centreId = scope.centreId() == null ? TenantFilters.NO_CENTRE : scope.centreId();
            session.enableFilter(TenantFilters.CENTRE_TENANT)
                    .setParameter(TenantFilters.CENTRE_ID_PARAM, centreId);
        } else {
            session.disableFilter(TenantFilters.CENTRE_TENANT);
        }
    }

    /**
     * Verifies that a row reached by primary key belongs to the caller.
     *
     * @throws CrossTenantAccessException mapped to 404, never 403, because a
     *         403 confirms the record exists
     */
    private void verifyOwnership(Object entity, Object id) {
        if (entity == null || !tenantOwned) {
            return;
        }
        TenantScope scope = TenantContext.current();
        if (!scope.isConstrained()) {
            return;
        }

        Long owning = ((TenantOwned) entity).resolveCentreId();
        Long requesting = scope.centreId();

        if (owning == null || !owning.equals(requesting)) {
            log.warn("Blocked cross-tenant access: {} {} owned by centre {}, requested by centre {}",
                    domainType.getSimpleName(), id, owning, requesting);
            throw new CrossTenantAccessException(
                    domainType.getSimpleName(), id, owning, requesting);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<T> findById(ID id) {
        applyTenantFilter();
        Optional<T> found = super.findById(id);
        found.ifPresent(entity -> verifyOwnership(entity, id));
        return found;
    }

    @Override
    @Transactional(readOnly = true)
    public T getReferenceById(ID id) {
        applyTenantFilter();
        T reference = super.getReferenceById(id);
        verifyOwnership(reference, id);
        return reference;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsById(ID id) {
        // Deliberately routed through findById rather than a count query.
        // A bare exists check that ignored the tenant would answer "yes, that
        // record exists" about another centre's patient, which is the
        // disclosure the 404 rule is there to prevent.
        applyTenantFilter();
        try {
            return findById(id).isPresent();
        } catch (CrossTenantAccessException e) {
            return false;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<T> findAll() {
        applyTenantFilter();
        return super.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<T> findAll(org.springframework.data.domain.Sort sort) {
        applyTenantFilter();
        return super.findAll(sort);
    }

    @Override
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<T> findAll(
            org.springframework.data.domain.Pageable pageable) {
        applyTenantFilter();
        return super.findAll(pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        applyTenantFilter();
        return super.count();
    }

    @Override
    @Transactional
    public <S extends T> S save(S entity) {
        applyTenantFilter();
        assertWriteAllowed(entity);
        return super.save(entity);
    }

    @Override
    @Transactional
    public void delete(T entity) {
        applyTenantFilter();
        assertWriteAllowed(entity);
        super.delete(entity);
    }

    /**
     * Stops a centre writing a row that belongs to, or would belong to,
     * another centre.
     *
     * Reads are the obvious risk and the one people test. Writes are the
     * quieter one: a centre coordinator posting a referral with someone else's
     * centre id would otherwise create a record inside another tenant.
     */
    private <S extends T> void assertWriteAllowed(S entity) {
        if (!tenantOwned) {
            return;
        }
        TenantScope scope = TenantContext.current();
        if (!scope.isConstrained()) {
            return;
        }

        Long owning = ((TenantOwned) entity).resolveCentreId();
        if (owning != null && !owning.equals(scope.centreId())) {
            throw new CrossTenantAccessException(
                    domainType.getSimpleName(), "new", owning, scope.centreId());
        }
    }
}
