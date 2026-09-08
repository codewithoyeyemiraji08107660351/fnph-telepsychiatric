package com.fnph.telepsychiatric.consultation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ParticipantTokenRepository extends JpaRepository<ParticipantToken, Long> {

    Optional<ParticipantToken> findByTokenHash(String tokenHash);

    Optional<ParticipantToken> findFirstByConsultationIdAndParticipantRoleAndRevokedAtIsNullOrderByIdDesc(
            Long consultationId, ParticipantRole role);

    /**
     * Kills every token for a session.
     *
     * Called when a clinician terminates. Without it, a participant ejected for
     * abuse or a privacy breach could simply rejoin with the token they already
     * hold.
     */
    @Modifying
    @Query("""
           update ParticipantToken t set t.revokedAt = :now, t.revokedReason = :reason
           where t.consultation.id = :consultationId and t.revokedAt is null
           """)
    int revokeAllForConsultation(@Param("consultationId") Long consultationId,
                                 @Param("now") LocalDateTime now,
                                 @Param("reason") String reason);
}
