package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.tenancy.UnscopedQuery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Care bundles awaiting release, and released.
 *
 * <h2>Release is an FNPH decision throughout</h2>
 *
 * The Hub Coordinator assembles and releases; the centre receives the result as
 * a {@link com.fnph.telepsychiatric.centre.CentreBundleReceipt}, which is
 * centre-scoped. No centre principal reads a bundle directly, which is why
 * these are annotated rather than parameterised.
 *
 * This repository is also why the batch had to include it: the component
 * queries on Prescription, Investigation and FollowUp are all justified by
 * "the bundle was resolved upstream". That justification is only worth
 * anything while the bundle lookups themselves are honest about who reaches
 * them.
 */
public interface ReleaseBundleRepository extends JpaRepository<ReleaseBundle, Long> {

    @UnscopedQuery(value = UnscopedQuery.Reason.HOSPITAL_QUEUE,
            detail = "Hub Coordinator assembles and releases bundles across all centres; "
                    + "guarded by release.read and release.write, neither held by a centre role")
    Optional<ReleaseBundle> findByPublicId(String publicId);

    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "appointmentId comes from an Appointment the clinician already resolved; "
                    + "FNPH pathway, where centre is null on the bundle")
    Optional<ReleaseBundle> findByAppointmentId(Long appointmentId);

    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "centreAppointmentId comes from a CentreAppointment resolved by the "
                    + "consulting doctor in CentreClinicalService")
    Optional<ReleaseBundle> findByCentreAppointmentId(Long centreAppointmentId);

    /**
     * The FNPH release desk: every hospital bundle in the given states, oldest
     * first because the wait is the patient's.
     *
     * The inner join on appointment is what keeps centre bundles off this desk;
     * those belong to the centre pathway. Appointment and patient are fetched
     * because the desk shows who each bundle is for, and open-in-view is off.
     */
    @UnscopedQuery(value = UnscopedQuery.Reason.HOSPITAL_QUEUE,
            detail = "Hub Coordinator's release desk across the FNPH pathway; guarded by "
                    + "release_bundle.read, which no centre role holds")
    @Query("""
           select b from ReleaseBundle b
           join fetch b.appointment a
           join fetch a.patient
           left join fetch a.doctor
           where b.status in :statuses
           order by b.createdAt asc
           """)
    List<ReleaseBundle> findFnphDesk(@Param("statuses") Collection<BundleStatus> statuses);
}
