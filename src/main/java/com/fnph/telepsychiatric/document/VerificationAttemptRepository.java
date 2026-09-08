package com.fnph.telepsychiatric.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface VerificationAttemptRepository extends JpaRepository<VerificationAttempt, Long> {

    /** Guesses from one address. The signal that someone is probing tokens. */
    @Query("""
           select count(a) from VerificationAttempt a
           where a.ipAddress = :ip and a.attemptedAt > :since
             and a.outcome = com.fnph.telepsychiatric.document.VerificationOutcome.NOT_FOUND
           """)
    long countRecentMissesForIp(@Param("ip") String ip, @Param("since") LocalDateTime since);
}
