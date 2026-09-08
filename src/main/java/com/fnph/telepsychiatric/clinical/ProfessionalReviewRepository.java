package com.fnph.telepsychiatric.clinical;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

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
           where r.reviewer.id = :reviewerId and r.submittedAt is null
           order by r.assignedAt asc
           """)
    List<ProfessionalReview> findOpenQueue(@Param("reviewerId") Long reviewerId);

    /** Concerns waiting on the Hub Coordinator. Never routed back to the doctor. */
    @Query("""
           select r from ProfessionalReview r
           where r.queryRaised = true and r.submittedToHubAt is not null
           order by r.submittedToHubAt asc
           """)
    List<ProfessionalReview> findRaisedQueries();
}
