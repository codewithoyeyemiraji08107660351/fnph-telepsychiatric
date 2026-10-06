package com.fnph.telepsychiatric.clinical;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClinicalEditRevisionRepository extends JpaRepository<ClinicalEditRevision, Long> {

    /** Newest first; rows of one save share an edit group. */
    List<ClinicalEditRevision> findAllByAppointmentIdOrderByCreatedAtDescIdAsc(Long appointmentId);
}
