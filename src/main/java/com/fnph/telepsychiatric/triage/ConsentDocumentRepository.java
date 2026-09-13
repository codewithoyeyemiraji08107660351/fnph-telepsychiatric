package com.fnph.telepsychiatric.triage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConsentDocumentRepository extends JpaRepository<ConsentDocument, Long> {

    Optional<ConsentDocument> findByPublicId(String publicId);

    /** The version currently in force for an audience. */
    Optional<ConsentDocument> findFirstByAudienceAndStatusOrderByEffectiveFromDesc(
            String audience, String status);

    List<ConsentDocument> findAllByAudienceOrderByCreatedAtDesc(String audience);
}