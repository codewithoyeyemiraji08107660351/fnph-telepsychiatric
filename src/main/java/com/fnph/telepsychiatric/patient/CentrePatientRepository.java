package com.fnph.telepsychiatric.patient;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Every method here is tenant-scoped automatically, because the repository base
 * class enables the filter before the query runs. None of these signatures
 * mentions a centre id, and none of them needs to.
 */
public interface CentrePatientRepository extends JpaRepository<CentrePatient, Long> {

    Optional<CentrePatient> findByPublicId(String publicId);

    /**
     * Centre-local identifiers are unique per centre, not globally, so this can
     * return a different patient at a different centre. The tenant filter is
     * what makes it unambiguous rather than the query.
     */
    Optional<CentrePatient> findByCentrePatientId(String centrePatientId);

    List<CentrePatient> findAllByIsActiveTrueOrderByLastNameAsc();

    @Query("""
           select p from CentrePatient p
           where p.deleted = false
             and (lower(p.firstName) like lower(concat('%', :term, '%'))
               or lower(p.lastName)  like lower(concat('%', :term, '%'))
               or p.centrePatientId = :term)
           """)
    List<CentrePatient> search(@Param("term") String term);
}
