package com.fnph.telepsychiatric.consultation;

import com.fnph.telepsychiatric.tenancy.UnscopedQuery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Join tokens for consultation rooms.
 *
 * <h2>A token is a credential, not a record to be looked up</h2>
 *
 * Nothing here takes a centre, and that is correct rather than convenient. The
 * token hash is the authorisation: a participant joining presents it, and
 * whoever holds it is by definition entitled to the session it opens. Adding a
 * centre predicate would require the joiner to already be authenticated as a
 * centre user, which defeats the point of a link a patient can open from an
 * SMS.
 *
 * The revocations are keyed by a consultation the clinician has already
 * resolved.
 */
public interface ParticipantTokenRepository extends JpaRepository<ParticipantToken, Long> {

    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SECRET,
            detail = "the token hash is the credential presented at join time; possession is "
                    + "the authorisation, and the joiner may be unauthenticated")
    Optional<ParticipantToken> findByTokenHash(String tokenHash);

    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "consultationId comes from a Consultation the clinician already resolved "
                    + "and owns")
    Optional<ParticipantToken> findFirstByConsultationIdAndParticipantRoleAndRevokedAtIsNullOrderByIdDesc(
            Long consultationId, ParticipantRole role);

    /**
     * Centre twin of {@link #revokeAllForConsultation}. Needed because the two
     * pathways hold their tokens on different associations, and a terminated
     * centre session must kill its tokens for the same reason.
     */
    @Modifying
    @Query("""
           update ParticipantToken t set t.revokedAt = :now, t.revokedReason = :reason
           where t.centreConsultation.id = :centreConsultationId and t.revokedAt is null
           """)
    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "centreConsultationId comes from a CentreConsultation the terminating "
                    + "clinician already resolved; revoking is fail-safe, not a disclosure")
    int revokeAllForCentreConsultation(@Param("centreConsultationId") Long centreConsultationId,
                                       @Param("now") LocalDateTime now,
                                       @Param("reason") String reason);

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
    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "consultationId comes from a Consultation the terminating clinician "
                    + "already resolved; revoking is fail-safe, not a disclosure")
    int revokeAllForConsultation(@Param("consultationId") Long consultationId,
                                 @Param("now") LocalDateTime now,
                                 @Param("reason") String reason);
}