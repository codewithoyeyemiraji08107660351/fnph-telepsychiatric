package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.consultation.CentreConsultationNote;
import com.fnph.telepsychiatric.tenancy.UnscopedQuery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
            detail = "keyed by centre appointment id, which the caller resolved "
                    + "through a centre-scoped lookup before reaching this")
    @Query("""
           select n from CentreConsultationNote n
             join n.centreConsultation c
            where c.centreAppointment.id = :centreAppointmentId
              and n.supersededAt is null
           """)
    Optional<ReleaseBundle> findByCentreAppointmentId(Long centreAppointmentId);
}