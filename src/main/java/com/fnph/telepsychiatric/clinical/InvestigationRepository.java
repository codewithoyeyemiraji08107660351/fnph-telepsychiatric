package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.tenancy.UnscopedQuery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Investigation requests, for both pathways.
 *
 * Scoping follows PrescriptionRepository: a centre reads investigations through
 * the release bundle, never from here, so these are annotated rather than
 * parameterised. See that class for the full reasoning.
 */
public interface InvestigationRepository extends JpaRepository<Investigation, Long> {

    @UnscopedQuery(value = UnscopedQuery.Reason.HOSPITAL_QUEUE,
            detail = "clinician path only; no centre role holds investigation.write, and the "
                    + "call sites assert clinician ownership")
    Optional<Investigation> findByPublicId(String publicId);

    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "patientId is CurrentUser.require().getPatientId() for "
                    + "GET /investigations/mine, guarded by investigation.read_own")
    List<Investigation> findAllByPatientIdOrderByCreatedAtDesc(Long patientId);

    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "bundleId comes from a ReleaseBundle resolved by the releasing hospital "
                    + "principal in ReleaseService")
    List<Investigation> findAllByBundleId(Long bundleId);
}