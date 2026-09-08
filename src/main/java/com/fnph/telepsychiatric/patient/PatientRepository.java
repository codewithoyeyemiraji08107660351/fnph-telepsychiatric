package com.fnph.telepsychiatric.patient;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PatientRepository extends JpaRepository<Patient, Long> {

    Optional<Patient> findByEhrNumber(String ehrNumber);

    Optional<Patient> findByPublicId(String publicId);

    boolean existsByEhrNumber(String ehrNumber);

    /** Accounts whose details changed in a later snapshot. HIM's review queue. */
    Page<Patient> findAllByDriftFlaggedTrueOrderByDriftFlaggedAtDesc(Pageable pageable);

    long countByDriftFlaggedTrue();
}
