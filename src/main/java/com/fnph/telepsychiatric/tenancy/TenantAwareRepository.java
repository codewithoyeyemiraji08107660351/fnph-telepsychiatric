package com.fnph.telepsychiatric.tenancy;

import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * The base class every Spring Data repository in this application inherits.
 *
 * <h2>What this class does and does not cover</h2>
 *
 * It covers the inherited {@link SimpleJpaRepository} methods, which it
 * overrides one by one below. Two mechanisms are applied to them:
 *
 * <b>1. The Hibernate filter</b>, enabled on the session before the query
 * runs, appending {@code centre_id = :centreId}.
 *
 * <b>2. An explicit ownership check</b> on anything reached by primary key,
 * because Hibernate filters are documented not to apply to
 * {@code EntityManager.find()}. Altering a record identifier in a URL goes
 * straight to a primary key lookup, which the filter would let through.
 *
 * <h2>It does NOT cover derived queries</h2>
 *
 * An earlier version of this documentation claimed the filter reached "JPQL,
 * criteria and derived queries alike, so a repository method written next year
 * is covered without its author knowing the filter exists". That was false and
 * it is the reason nobody checked for months.
 *
 * A derived query such as {@code findByPublicId} is executed by Spring Data's
 * {@code PartTreeJpaQuery}, which never invokes any method on this class, so
 * {@link #applyTenantFilter()} never runs and no filter is enabled.
 * {@code TenantIsolationTest} tests 06 and 06b demonstrate this against a real
 * MySQL: a centre principal scoped to centre A calling
 * {@code findByCentrePatientId} retrieves centre B's patient.
 *
 * An aspect cannot close this. Spring Data builds its own proxy with the
 * transaction interceptor inside it, so advice added to the repository
 * interface runs before the transaction opens and therefore before a Hibernate
 * session exists to enable a filter on.
 *
 * Until the derived-query rule and its guard test are in place, <b>every
 * derived query on a tenant-owned entity must name the centre in its own
 * signature</b>: {@code findByCentreIdAndPublicId(Long, String)}, not
 * {@code findByPublicId(String)}. Nothing in this class will do it for you.
 *
 * The same gap exists for anything reached by navigating an association from a
 * permitted row, which is why the service layer still validates before it
 * follows a reference into another aggregate.
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

    /** True when the current principal is restricted to a single centre. */
    private boolean constrained() {
        return tenantOwned && TenantContext.current().isConstrained();
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

    // ---------------------------------------------------------------- reads

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

    /**
     * Builds its own query rather than delegating to {@link #findAll()}, so it
     * needs the filter applied here. A caller passing a list of another
     * centre's identifiers would otherwise receive those rows.
     */
    @Override
    @Transactional(readOnly = true)
    public List<T> findAllById(Iterable<ID> ids) {
        applyTenantFilter();
        List<T> found = super.findAllById(ids);
        if (constrained()) {
            found.forEach(entity -> verifyOwnership(entity, "batch"));
        }
        return found;
    }

    @Override
    @Transactional(readOnly = true)
    public List<T> findAll() {
        applyTenantFilter();
        return super.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<T> findAll(Sort sort) {
        applyTenantFilter();
        return super.findAll(sort);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<T> findAll(Pageable pageable) {
        applyTenantFilter();
        return super.findAll(pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public <S extends T> Optional<S> findOne(Example<S> example) {
        applyTenantFilter();
        return super.findOne(example);
    }

    @Override
    @Transactional(readOnly = true)
    public <S extends T> List<S> findAll(Example<S> example) {
        applyTenantFilter();
        return super.findAll(example);
    }

    @Override
    @Transactional(readOnly = true)
    public <S extends T> Page<S> findAll(Example<S> example, Pageable pageable) {
        applyTenantFilter();
        return super.findAll(example, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        applyTenantFilter();
        return super.count();
    }

    @Override
    @Transactional(readOnly = true)
    public <S extends T> long count(Example<S> example) {
        applyTenantFilter();
        return super.count(example);
    }

    // --------------------------------------------------------------- writes

    @Override
    @Transactional
    public <S extends T> S save(S entity) {
        applyTenantFilter();
        assertWriteAllowed(entity);
        return super.save(entity);
    }

    /**
     * Does not delegate to {@link #save}, so the write check is repeated here.
     */
    @Override
    @Transactional
    public <S extends T> S saveAndFlush(S entity) {
        applyTenantFilter();
        assertWriteAllowed(entity);
        return super.saveAndFlush(entity);
    }

    @Override
    @Transactional
    public void delete(T entity) {
        applyTenantFilter();
        assertWriteAllowed(entity);
        super.delete(entity);
    }

    /**
     * Loads through {@link #findById} so a centre cannot delete by guessing an
     * identifier. A missing or foreign row is a silent no-op, matching the
     * 404-not-403 rule.
     */
    @Override
    @Transactional
    public void deleteById(ID id) {
        findById(id).ifPresent(this::delete);
    }

    @Override
    @Transactional
    public void deleteAll(Iterable<? extends T> entities) {
        applyTenantFilter();
        entities.forEach(this::assertWriteAllowed);
        super.deleteAll(entities);
    }

    @Override
    @Transactional
    public void deleteAllById(Iterable<? extends ID> ids) {
        ids.forEach(this::deleteById);
    }

    /**
     * Refused for a centre principal: an unqualified delete-everything from a
     * centre account is never a legitimate operation, and the filter does not
     * constrain it.
     */
    @Override
    @Transactional
    public void deleteAll() {
        assertUnscopedBulkAllowed("deleteAll");
        super.deleteAll();
    }

    /**
     * Bulk deletes issue a single DELETE without loading entities, so neither
     * the filter nor the ownership check applies. Refused rather than silently
     * unguarded.
     */
    @Override
    @Transactional
    public void deleteAllInBatch() {
        assertUnscopedBulkAllowed("deleteAllInBatch");
        super.deleteAllInBatch();
    }

    @Override
    @Transactional
    public void deleteAllInBatch(Iterable<T> entities) {
        applyTenantFilter();
        entities.forEach(this::assertWriteAllowed);
        super.deleteAllInBatch(entities);
    }

    @Override
    @Transactional
    public void deleteAllByIdInBatch(Iterable<ID> ids) {
        assertUnscopedBulkAllowed("deleteAllByIdInBatch");
        super.deleteAllByIdInBatch(ids);
    }

    private void assertUnscopedBulkAllowed(String operation) {
        if (constrained()) {
            log.warn("Blocked unscoped bulk operation {} on {} by centre {}",
                    operation, domainType.getSimpleName(), TenantContext.current().centreId());
            throw new CrossTenantAccessException(
                    domainType.getSimpleName(), operation, null,
                    TenantContext.current().centreId());
        }
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