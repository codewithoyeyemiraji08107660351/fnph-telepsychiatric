package com.fnph.telepsychiatric.scheduling;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SlotRepository extends JpaRepository<Slot, Long> {

    Optional<Slot> findByPublicId(String publicId);

    /**
     * Claims a slot under a pessimistic write lock.
     *
     * Optimistic locking on the version column catches the conflict, but only
     * after both transactions have done their work. For the specific case of
     * two patients on the same slot, taking the lock first turns a retry into a
     * short wait and gives the loser a clean "someone just took that time"
     * rather than an optimistic-lock failure surfacing as a 500.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Slot s where s.id = :id")
    Optional<Slot> findByIdForUpdate(@Param("id") Long id);

    @Query("""
           select s from Slot s
           where s.publication.audience = :audience
             and s.publication.status = com.fnph.telepsychiatric.scheduling.PublicationStatus.PUBLISHED
             and s.state = com.fnph.telepsychiatric.scheduling.SlotState.AVAILABLE
             and s.startAt > :notBefore
           order by s.startAt asc
           """)
    List<Slot> findBookable(@Param("audience") ScheduleAudience audience,
                            @Param("notBefore") LocalDateTime notBefore);

    List<Slot> findAllByPublicationIdOrderByStartAtAsc(Long publicationId);

    /**
     * Available slots for a date, in time order.
     *
     * The caller groups them by start time. A patient chooses a time; which of
     * the rooms running at that time they get is the Hub Coordinator's decision
     * at approval, so showing four identical times would be noise.
     */
    @Query("""
           select s from Slot s
           where s.publication.audience = :audience
             and s.publication.serviceDate = :date
             and s.publication.status = com.fnph.telepsychiatric.scheduling.PublicationStatus.PUBLISHED
             and s.state = com.fnph.telepsychiatric.scheduling.SlotState.AVAILABLE
             and s.startAt > :notBefore
           order by s.startAt asc
           """)
    List<Slot> findAvailableOn(@Param("audience") ScheduleAudience audience,
                               @Param("date") java.time.LocalDate date,
                               @Param("notBefore") LocalDateTime notBefore);

    long countByPublicationIdAndState(Long publicationId, SlotState state);

    /** A room's future slots in one state, for taking the room out of service. */
    List<Slot> findAllByRoomIdAndStateAndStartAtAfter(Long roomId, SlotState state, LocalDateTime after);

    long countByRoomIdAndStateInAndStartAtAfter(Long roomId, java.util.Collection<SlotState> states, LocalDateTime after);

    /** Returns lapsed holds to the pool. Run on a schedule. */
    @Modifying
    @Query("""
           update Slot s set s.state = com.fnph.telepsychiatric.scheduling.SlotState.AVAILABLE
           where s.state = com.fnph.telepsychiatric.scheduling.SlotState.HELD
             and exists (select h from SlotHold h
                         where h.slot = s and h.releasedAt is null and h.expiresAt <= :now)
           """)
    int releaseExpiredHolds(@Param("now") LocalDateTime now);
}
