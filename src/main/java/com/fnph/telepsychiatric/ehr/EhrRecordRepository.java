package com.fnph.telepsychiatric.ehr;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EhrRecordRepository
        extends JpaRepository<EhrVerificationRecord, Long> {

    Optional<EhrVerificationRecord>
    findByEhrImportIdAndEhrNumber(
            Long importId,
            String ehrNumber
    );

    /**
     * Rows in the active snapshot whose name or phone differs
     * from an already activated patient account.
     *
     * These differences are surfaced to HIM rather than silently
     * rebinding an existing account.
     */
    @Query("""
           select r
           from EhrVerificationRecord r,
                com.fnph.telepsychiatric.patient.Patient p
           where r.ehrImport.id = :importId
             and p.ehrNumber = r.ehrNumber
             and p.activatedAt is not null
             and (
                    concat(p.firstName, ' ', p.lastName) <> r.fullName
                    or (
                        p.phoneNumber is not null
                        and r.phoneMasked is not null
                        and p.phoneNumber not like
                            concat('%', substring(r.phoneMasked, -4))
                    )
             )
           """)
    List<EhrVerificationRecord>
    findDriftAgainstActivePatients(
            @Param("importId") Long importId
    );

    long countByEhrImportId(Long importId);
}