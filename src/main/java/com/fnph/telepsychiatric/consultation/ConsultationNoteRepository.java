package com.fnph.telepsychiatric.consultation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConsultationNoteRepository extends JpaRepository<ConsultationNote, Long> {

    Optional<ConsultationNote> findByPublicId(String publicId);

    /** The current version: the one nothing supersedes. */
    Optional<ConsultationNote> findFirstByConsultationIdAndSupersededAtIsNullOrderByVersionDesc(
            Long consultationId);

    /** Every version, oldest first. The amendment history. */
    List<ConsultationNote> findAllByConsultationIdOrderByVersionAsc(Long consultationId);
}
