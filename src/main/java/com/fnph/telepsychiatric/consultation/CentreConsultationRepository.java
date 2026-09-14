package com.fnph.telepsychiatric.consultation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CentreConsultationRepository
        extends JpaRepository<CentreConsultation, Long> {

    /**
     * Names the centre because CentreConsultation is tenant-owned and derived
     * queries are not covered by the Hibernate filter. See the class javadoc on
     * TenantAwareRepository: a lookup by public id alone reaches another
     * centre's consultation.
     */
    Optional<CentreConsultation> findByCentreIdAndPublicId(Long centreId, String publicId);

    Optional<CentreConsultation> findByCentreAppointmentId(Long centreAppointmentId);

    /** For the session clock. Hospital-scoped, so no centre predicate. */
    List<CentreConsultation> findAllByEndedAtIsNullAndScheduledEndAtIsNotNullAndScheduledStartAtLessThanEqual(
            LocalDateTime now);

    List<CentreConsultation> findAllByRoomNameIsNotNullAndRoomDeletedAtIsNullAndRoomExpiresAtLessThanEqual(
            LocalDateTime now);

    /** Running sessions, for the clock. Hospital-scoped; no centre predicate. */
    @Query("""
           select c from CentreConsultation c
           where c.endedAt is null
             and c.scheduledEndAt is not null
             and c.scheduledStartAt <= :now
           """)
    List<CentreConsultation> findRunning(@Param("now") LocalDateTime now);

    @Query("""
           select c from CentreConsultation c
           where c.roomName is not null
             and c.roomDeletedAt is null
             and c.roomExpiresAt <= :now
           """)
    List<CentreConsultation> findExpiredRooms(@Param("now") LocalDateTime now);

    /** Hospital-scoped. Centre callers use findByCentreIdAndPublicId. */
    Optional<CentreConsultation> findByPublicId(String publicId);
}