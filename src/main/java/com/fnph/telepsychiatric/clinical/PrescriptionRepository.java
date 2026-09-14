package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.tenancy.UnscopedQuery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Prescriptions, for both pathways.
 *
 * <h2>Why nothing here takes a centre</h2>
 *
 * A Prescription carries both a {@code patient} and a {@code centrePatient},
 * and {@code centre} is null on the FNPH pathway. A centre never queries this
 * repository: centre staff read clinical output through
 * {@link com.fnph.telepsychiatric.centre.CentreBundleReceipt} and the release
 * bundle, which are centre-scoped, so the prescription reaches them already
 * resolved.
 *
 * Adding a centre parameter here would therefore be decoration on paths no
 * centre principal reaches, and decoration is worse than an annotation because
 * it implies a control is load-bearing when it is not. Every method below says
 * which principal reaches it instead.
 *
 * If a centre-facing read of this repository is ever added, it takes a centre.
 */
public interface PrescriptionRepository extends JpaRepository<Prescription, Long> {

    /**
     * Clinician lookup when superseding a prescription.
     *
     * Reached from {@code ClinicalService.supersedePrescription} and
     * {@code CentreClinicalService.supersedePrescription}, both authored by the
     * FNPH doctor who owns the consultation. Ownership is enforced in those
     * services by clinician, which is a stronger check than centre.
     */
    @UnscopedQuery(value = UnscopedQuery.Reason.HOSPITAL_QUEUE,
            detail = "prescribing doctor supersedes their own prescription; both call sites "
                    + "assert clinician ownership, and no centre role holds prescription.write")
    Optional<Prescription> findByPublicId(String publicId);

    /**
     * The patient's own prescriptions, for {@code GET /prescriptions/mine}.
     *
     * The patient id comes from the authenticated principal, not from the
     * request, so the caller cannot name someone else's.
     */
    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "patientId is CurrentUser.require().getPatientId(), taken from the session "
                    + "rather than the request, and guarded by prescription.read_own")
    List<Prescription> findAllByPatientIdOrderByCreatedAtDesc(Long patientId);

    /**
     * Components of one release bundle.
     *
     * Called only from ReleaseService, with a bundle the Hub Coordinator has
     * already resolved through a hospital-scoped path.
     */
    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "bundleId comes from a ReleaseBundle resolved by the releasing hospital "
                    + "principal in ReleaseService")
    List<Prescription> findAllByBundleId(Long bundleId);
}