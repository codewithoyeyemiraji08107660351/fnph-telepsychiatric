package com.fnph.telepsychiatric.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SessionRepository extends JpaRepository<UserSession, Long> {

    Optional<UserSession> findByRefreshTokenHash(String refreshTokenHash);

    Optional<UserSession> findByPublicIdAndUserId(String publicId, Long userId);

    Optional<UserSession> findByPublicId(String publicId);

    @Query("""
           select s from UserSession s
           where s.user.id = :userId and s.revokedAt is null and s.replacedAt is null
             and s.expiresAt > :now
           order by s.lastSeenAt desc
           """)
    List<UserSession> findActiveByUser(@Param("userId") Long userId,
                                       @Param("now") LocalDateTime now);

    /**
     * Ends every token descended from one sign-in.
     *
     * Called when a replaced refresh token is presented again. That is either a
     * client retrying after a dropped response or a stolen token running
     * alongside the real one, and the server cannot tell which, so it assumes
     * the worse case.
     */
    @Modifying
    @Query("""
           update UserSession s
              set s.revokedAt = :now, s.revokedBy = :actor, s.revokedReason = :reason
            where s.familyId = :familyId and s.revokedAt is null
           """)
    int revokeFamily(@Param("familyId") String familyId,
                     @Param("now") LocalDateTime now,
                     @Param("actor") String actor,
                     @Param("reason") String reason);

    @Modifying
    @Query("""
           update UserSession s
              set s.revokedAt = :now, s.revokedBy = :actor, s.revokedReason = :reason
            where s.user.id = :userId and s.revokedAt is null
              and (:exceptSessionId is null or s.id <> :exceptSessionId)
           """)
    int revokeAllForUser(@Param("userId") Long userId,
                         @Param("exceptSessionId") Long exceptSessionId,
                         @Param("now") LocalDateTime now,
                         @Param("actor") String actor,
                         @Param("reason") String reason);

    @Modifying
    @Query("update UserSession s set s.lastSeenAt = :now where s.id = :id")
    void touch(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Query("select count(s) from UserSession s where s.user.id = :userId "
            + "and s.revokedAt is null and s.replacedAt is null and s.expiresAt > :now")
    long countActiveByUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
