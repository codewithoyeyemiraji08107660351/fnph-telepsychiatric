package com.fnph.telepsychiatric.ehr;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface EhrLookupAttemptRepository extends JpaRepository<EhrLookupAttempt, Long> {

    /**
     * Failures from one address across any EHR numbers.
     *
     * This is the enumeration signal. A patient mistyping their own number
     * produces two or three failures against one number; walking the range
     * produces many failures against many.
     */
    @Query("""
           select count(a) from EhrLookupAttempt a
           where a.ipAddress = :ip and a.attemptedAt > :since
             and a.outcome in (com.fnph.telepsychiatric.ehr.LookupOutcome.NOT_FOUND,
                               com.fnph.telepsychiatric.ehr.LookupOutcome.CORROBORATION_FAILED,
                               com.fnph.telepsychiatric.ehr.LookupOutcome.RECORD_INACTIVE)
           """)
    long countRecentFailuresForIp(@Param("ip") String ip, @Param("since") LocalDateTime since);

    @Query("""
           select count(a) from EhrLookupAttempt a
           where a.ehrNumberAttempted = :ehrNumber and a.attemptedAt > :since
             and a.outcome in (com.fnph.telepsychiatric.ehr.LookupOutcome.NOT_FOUND,
                               com.fnph.telepsychiatric.ehr.LookupOutcome.CORROBORATION_FAILED,
                               com.fnph.telepsychiatric.ehr.LookupOutcome.RECORD_INACTIVE)
           """)
    long countRecentFailuresForNumber(@Param("ehrNumber") String ehrNumber,
                                      @Param("since") LocalDateTime since);
}
