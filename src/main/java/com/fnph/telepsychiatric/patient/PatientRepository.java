package com.fnph.telepsychiatric.patient;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PatientRepository extends JpaRepository<Patient, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from Patient p where p.id = :id")
    Optional<Patient> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    Optional<Patient> findByEhrNumber(String ehrNumber);

    Optional<Patient> findByPublicId(String publicId);

    boolean existsByEhrNumber(String ehrNumber);

    /** Accounts whose details changed in a later snapshot. HIM's review queue. */
    Page<Patient> findAllByDriftFlaggedTrueOrderByDriftFlaggedAtDesc(Pageable pageable);

    @org.springframework.data.jpa.repository.Query("""
           select p from Patient p
           where p.deleted = false
             and (p.ehrNumber = :term
               or lower(p.firstName) like lower(concat('%', :term, '%'))
               or lower(p.lastName)  like lower(concat('%', :term, '%')))
           """)
    java.util.List<Patient> search(
            @org.springframework.data.repository.query.Param("term") String term,
            Pageable pageable);

    long countByDriftFlaggedTrue();
}
