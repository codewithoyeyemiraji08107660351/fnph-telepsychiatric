package com.fnph.telepsychiatric.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, Long> {

    /** Failures against one username, for account lockout. */
    @Query("""
           select count(a) from LoginAttempt a
           where a.usernameAttempted = :username
             and a.attemptedAt > :since
             and a.outcome in (com.fnph.telepsychiatric.session.LoginOutcome.BAD_CREDENTIALS,
                               com.fnph.telepsychiatric.session.LoginOutcome.MFA_FAILED)
           """)
    long countRecentFailuresForUsername(@Param("username") String username,
                                        @Param("since") LocalDateTime since);

    /**
     * Failures from one address across all usernames, for rate limiting.
     *
     * Separate from the per-username count on purpose. Locking only by username
     * lets an attacker spray one password across many accounts without ever
     * tripping a threshold, and it also lets them lock a known clinician out of
     * a clinical system on demand.
     */
    @Query("""
           select count(a) from LoginAttempt a
           where a.ipAddress = :ip and a.attemptedAt > :since
             and a.outcome <> com.fnph.telepsychiatric.session.LoginOutcome.SUCCESS
           """)
    long countRecentFailuresForIp(@Param("ip") String ip, @Param("since") LocalDateTime since);
}
