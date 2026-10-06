package com.fnph.telepsychiatric.clinical;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface ProfessionalReviewRepository extends JpaRepository<ProfessionalReview, Long> {

    Optional<ProfessionalReview> findByPublicId(String publicId);

    Optional<ProfessionalReview> findByPrescriptionIdAndReviewType(Long prescriptionId, ReviewType type);

    Optional<ProfessionalReview> findByInvestigationIdAndReviewType(Long investigationId, ReviewType type);

    /**
     * A reviewer's queue: assigned to them, not yet submitted.
     *
     * Assigned rather than shared, because the Hub Coordinator named this
     * professional at approval and a shared pool would let anyone pick up work
     * the team was not told about.
     */
    @Query("""
           select r from ProfessionalReview r
           left join fetch r.prescription
           left join fetch r.investigation
           where r.reviewer.id = :reviewerId and r.submittedAt is null
           order by r.assignedAt asc
           """)
    List<ProfessionalReview> findOpenQueue(@Param("reviewerId") Long reviewerId);

    /** Concerns waiting on the Hub Coordinator. Never routed back to the doctor. */
    @Query("""
           select r from ProfessionalReview r
           left join fetch r.prescription
           left join fetch r.investigation
           where r.queryRaised = true and r.submittedToHubAt is not null
           order by r.submittedToHubAt asc
           """)
    List<ProfessionalReview> findRaisedQueries();

    /** Every review attached to one bundle's prescriptions and investigations. */
    @Query("""
           select r from ProfessionalReview r
           left join fetch r.prescription p
           left join fetch r.investigation i
           left join fetch r.reviewer
           where p.bundle.id = :bundleId or i.bundle.id = :bundleId
           order by r.assignedAt asc
           """)
    List<ProfessionalReview> findAllByBundleId(@Param("bundleId") Long bundleId);

    /** One light row per review across many bundles, for the workflow board. */
    @Query("""
                     select new com.fnph.telepsychiatric.clinical.ReviewDigest(
                         pb.id, ib.id, r.reviewType, rv.id, r.openedAt, r.submittedAt, r.queryRaised)
           from ProfessionalReview r
           left join r.prescription p
           left join p.bundle pb
           left join r.investigation i
           left join i.bundle ib
           left join r.reviewer rv
           where pb.id in :bundleIds or ib.id in :bundleIds
           """)
    List<ReviewDigest> findDigestsByBundleIds(@Param("bundleIds") Collection<Long> bundleIds);
}
