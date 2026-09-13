package com.fnph.telepsychiatric.triage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TriageQuestionSetRepository extends JpaRepository<TriageQuestionSet, Long> {

    Optional<TriageQuestionSet> findByPublicId(String publicId);

    Optional<TriageQuestionSet> findFirstByAudienceAndStatusOrderByEffectiveFromDesc(
            String audience, String status);

    List<TriageQuestionSet> findAllByAudienceOrderByCreatedAtDesc(String audience);
}