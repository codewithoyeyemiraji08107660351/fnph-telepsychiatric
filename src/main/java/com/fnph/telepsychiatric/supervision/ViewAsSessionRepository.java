package com.fnph.telepsychiatric.supervision;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ViewAsSessionRepository extends JpaRepository<ViewAsSession, Long> {

    Optional<ViewAsSession> findByPublicId(String publicId);

    @Query("""
           select s from ViewAsSession s
           where s.administrator.id = :administratorId
             and s.endedAt is null and s.expiresAt > :now
           """)
    Optional<ViewAsSession> findOpenForAdministrator(@Param("administratorId") Long administratorId,
                                                     @Param("now") LocalDateTime now);

    @Query("""
           select s from ViewAsSession s
           where (:administratorId is null or s.administrator.id = :administratorId)
             and (:targetUserId is null or s.targetUser.id = :targetUserId)
             and s.startedAt between :from and :to
           order by s.startedAt desc
           """)
    Page<ViewAsSession> search(@Param("administratorId") Long administratorId,
                               @Param("targetUserId") Long targetUserId,
                               @Param("from") LocalDateTime from,
                               @Param("to") LocalDateTime to,
                               Pageable pageable);

    @Modifying
    @Query("""
           update ViewAsSession s set s.endedAt = :now, s.endReason = 'Expired'
           where s.endedAt is null and s.expiresAt <= :now
           """)
    int closeExpired(@Param("now") LocalDateTime now);

    @Modifying
    @Query("update ViewAsSession s set s.actionsPerformed = s.actionsPerformed + 1 where s.id = :id")
    void incrementActions(@Param("id") Long id);
}
