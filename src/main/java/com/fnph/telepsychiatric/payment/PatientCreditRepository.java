package com.fnph.telepsychiatric.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PatientCreditRepository extends JpaRepository<PatientCredit, Long> {

    Optional<PatientCredit> findFirstByPatientIdOrderByIdDesc(Long patientId);

    List<PatientCredit> findAllByPatientIdOrderByIdDesc(Long patientId);

    /**
     * Balance derived from the ledger, not read from a cached field.
     *
     * Expired credits are excluded here rather than deleted, so the history of
     * why a credit existed survives its expiry.
     */
    @Query("""
           select coalesce(sum(case when c.direction = com.fnph.telepsychiatric.payment.LedgerDirection.CREDIT
                                    then c.amount else -c.amount end), 0)
           from PatientCredit c
           where c.patient.id = :patientId
             and (c.expiresAt is null or c.expiresAt > :now)
           """)
    BigDecimal deriveBalance(@Param("patientId") Long patientId, @Param("now") LocalDateTime now);
}
