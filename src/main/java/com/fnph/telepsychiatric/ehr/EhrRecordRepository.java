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

    /**
     * Snapshot rows for a number the patient typed: the exact row, plus any
     * row that matches once leading zeros are ignored on both sides.
     *
     * Why this exists: a hospital card prints "0351", but a spreadsheet export
     * stores the EHR column as a number and drops the zero, so the snapshot
     * holds "351". The patient types what is on the card and never matches.
     *
     * Several rows mean two patients whose numbers differ only by zeros. The
     * caller refuses to choose between them; it never guesses.
     */
    @Query("""
           select r
           from EhrVerificationRecord r
           where r.ehrImport.id = :importId
             and (
                    r.ehrNumber = :number
                    or trim(leading '0' from r.ehrNumber)
                       = trim(leading '0' from :number)
             )
           """)
    List<EhrVerificationRecord>
    findMatchingIgnoringLeadingZeros(
            @Param("importId") Long importId,
            @Param("number") String number
    );

    long countByEhrImportId(Long importId);
}