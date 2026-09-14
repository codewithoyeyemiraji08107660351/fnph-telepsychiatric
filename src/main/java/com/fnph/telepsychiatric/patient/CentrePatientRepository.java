package com.fnph.telepsychiatric.patient;

import com.fnph.telepsychiatric.tenancy.UnscopedQuery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * <h2>Every method here names the centre, because none of them can rely on the filter</h2>
 *
 * The previous version of this comment said the opposite: that the base class
 * enabled the filter before the query ran, so no signature needed to mention a
 * centre. That is true of the inherited CRUD methods and false of everything
 * declared here. A derived query and an {@code @Query} are both executed by
 * Spring Data without passing through
 * {@link com.fnph.telepsychiatric.tenancy.TenantAwareRepository}, so
 * {@code applyTenantFilter} never runs and no centre predicate is added.
 *
 * TenantIsolationTest 06 and 06b demonstrated it against MySQL: a centre
 * principal scoped to centre A called {@code findByCentrePatientId("B-0001")}
 * and received centre B's patient.
 *
 * So the centre is a parameter, visible in every signature, supplied from
 * {@link com.fnph.telepsychiatric.tenancy.TenantContext} at the call site.
 * Anything added here later does the same or carries an {@link UnscopedQuery}
 * reason, and TenantQueryScopingTest fails the build otherwise.
 */
public interface CentrePatientRepository extends JpaRepository<CentrePatient, Long> {

    Optional<CentrePatient> findByCentreIdAndPublicId(Long centreId, String publicId);

    /**
     * Centre-local identifiers are unique per centre, not globally. Without the
     * centre this returns whichever centre's patient happens to match, which is
     * exactly what test 06 caught.
     */
    Optional<CentrePatient> findByCentreIdAndCentrePatientId(Long centreId,
                                                             String centrePatientId);

    List<CentrePatient> findAllByCentreIdAndIsActiveTrueOrderByLastNameAsc(Long centreId);

    /**
     * Name search within one centre.
     *
     * The centre predicate is not optional. Without it a coordinator searching
     * a common first name receives matching patients from all 23 centres, which
     * discloses that a named person is a psychiatric patient somewhere in the
     * network. Wider than the altered-identifier case, and it needs no
     * identifier at all.
     */
    @Query("""
           select p from CentrePatient p
           where p.centre.id = :centreId
             and p.deleted = false
             and (lower(p.firstName) like lower(concat('%', :term, '%'))
               or lower(p.lastName)  like lower(concat('%', :term, '%'))
               or p.centrePatientId = :term)
           """)
    List<CentrePatient> search(@Param("centreId") Long centreId,
                               @Param("term") String term);

    /**
     * Hospital-scoped lookup, for FNPH staff working across centres.
     *
     * Kept as a separate method rather than making the centre nullable, so a
     * centre-facing path cannot reach every centre by passing null.
     */
    @UnscopedQuery(value = UnscopedQuery.Reason.HOSPITAL_QUEUE,
            detail = "Hub Coordinator and Doctor work across all centres; no centre role "
                    + "reaches this path")
    Optional<CentrePatient> findByPublicId(String publicId);
}