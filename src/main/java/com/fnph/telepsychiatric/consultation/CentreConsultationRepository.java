package com.fnph.telepsychiatric.consultation;

import com.fnph.telepsychiatric.tenancy.UnscopedQuery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Centre consultation sessions.
 *
 * <h2>Two kinds of access, and only one of them has a centre</h2>
 *
 * A centre reads its own session by identifier, so that lookup names the
 * centre. The clock does not: it is a scheduled sweep with no principal at all,
 * and scoping it to a centre would leave the other 22 centres' sessions
 * running past their slot end with rooms still open at the provider. The sweep
 * has to see everything or it is not a control.
 *
 * This file previously held each clock query twice, once derived and once as
 * an {@code @Query} with an identical predicate. The duplicates are gone; only
 * the {@code @Query} forms had callers.
 */
public interface CentreConsultationRepository
        extends JpaRepository<CentreConsultation, Long> {

    /**
     * Names the centre because CentreConsultation is tenant-owned and derived
     * queries are not covered by the Hibernate filter. See the class javadoc on
     * TenantAwareRepository: a lookup by public id alone reaches another
     * centre's consultation.
     */
    Optional<CentreConsultation> findByCentreIdAndPublicId(Long centreId, String publicId);

    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "centreAppointmentId comes from a CentreAppointment the consulting doctor "
                    + "already resolved; the appointment decides the centre")
    Optional<CentreConsultation> findByCentreAppointmentId(Long centreAppointmentId);

    /**
     * Running sessions, for the clock.
     *
     * Drives the two warnings and the close at slot end. Must span every
     * centre: a per-centre version would only ever close the sessions of
     * whichever centre happened to be in context, which is none of them,
     * because the scheduler has no centre in context.
     */
    @Query("""
           select c from CentreConsultation c
           where c.endedAt is null
             and c.scheduledEndAt is not null
             and c.scheduledStartAt <= :now
           """)
    @UnscopedQuery(value = UnscopedQuery.Reason.HOSPITAL_QUEUE,
            detail = "scheduled session clock runs with no principal; must see every centre's "
                    + "running sessions or their warnings and slot-end close never fire")
    List<CentreConsultation> findRunning(@Param("now") LocalDateTime now);

    /**
     * Sessions whose provider room is past its expiry and not yet deleted.
     *
     * The room is deleted at Daily as well as ended here, because ending the
     * session in this database does nothing to a room that still exists at the
     * provider: anyone holding a join link could reopen it.
     */
    @Query("""
           select c from CentreConsultation c
           where c.roomName is not null
             and c.roomDeletedAt is null
             and c.roomExpiresAt <= :now
           """)
    @UnscopedQuery(value = UnscopedQuery.Reason.HOSPITAL_QUEUE,
            detail = "scheduled room-expiry sweep runs with no principal; must see every "
                    + "centre's rooms or they are never deleted at the provider")
    List<CentreConsultation> findExpiredRooms(@Param("now") LocalDateTime now);

    /** Hospital-scoped. Centre callers use findByCentreIdAndPublicId. */
    @UnscopedQuery(value = UnscopedQuery.Reason.HOSPITAL_QUEUE,
            detail = "consulting doctor and Hub Coordinator join and supervise sessions across "
                    + "centres; every centre-facing read uses findByCentreIdAndPublicId")
    Optional<CentreConsultation> findByPublicId(String publicId);
}