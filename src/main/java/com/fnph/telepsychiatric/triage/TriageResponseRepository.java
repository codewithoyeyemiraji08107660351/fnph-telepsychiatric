package com.fnph.telepsychiatric.triage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TriageResponseRepository extends JpaRepository<TriageResponse, Long> {

    Optional<TriageResponse> findByPublicId(String publicId);

    List<TriageResponse> findAllByPatientIdOrderBySubmittedAtDesc(Long patientId);

    /** The most recent PROCEED, which is what a booking is allowed against. */
    Optional<TriageResponse> findFirstByPatientIdAndOutcomeOrderBySubmittedAtDesc(
            Long patientId, String outcome);
}