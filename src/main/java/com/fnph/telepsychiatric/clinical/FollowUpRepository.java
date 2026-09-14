package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.tenancy.UnscopedQuery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Follow-up recommendations, for both pathways.
 *
 * Scoping follows PrescriptionRepository. See that class for the reasoning.
 */
public interface FollowUpRepository extends JpaRepository<FollowUp, Long> {

    /**
     * Single follow-up, for {@code GET /follow-ups/{publicId}}.
     *
     * Guarded by {@code follow_up.read}, a clinician permission. This is the
     * one method in the batch where the justification rests on a read
     * permission rather than a write one, so it is the one to re-check if the
     * role matrix changes: if a centre role ever holds {@code follow_up.read},
     * this becomes a cross-centre read of a clinical recommendation.
     */
    @UnscopedQuery(value = UnscopedQuery.Reason.HOSPITAL_QUEUE,
            detail = "GET /follow-ups/{id} is guarded by follow_up.read, held by FNPH "
                    + "clinicians only; centre staff read follow-ups via the bundle receipt")
    Optional<FollowUp> findByPublicId(String publicId);

    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "patientId is CurrentUser.require().getPatientId() for "
                    + "GET /follow-ups/mine, guarded by follow_up.read_own")
    List<FollowUp> findAllByPatientIdOrderByCreatedAtDesc(Long patientId);

    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "bundleId comes from a ReleaseBundle resolved by the releasing hospital "
                    + "principal in ReleaseService")
    List<FollowUp> findAllByBundleId(Long bundleId);
}