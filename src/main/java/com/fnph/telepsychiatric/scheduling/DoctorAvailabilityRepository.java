package com.fnph.telepsychiatric.scheduling;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DoctorAvailabilityRepository extends JpaRepository<DoctorAvailability, Long> {

    List<DoctorAvailability> findAllByServiceDateOrderByStartAtAsc(LocalDate serviceDate);

    /**
     * Unavailable entries (leave, training, sickness) that overlap the slot.
     *
     * Doctors are assignable by default, like the rest of the team. The rota
     * no longer has to list a doctor before they can be assigned; it only
     * blocks a doctor marked unavailable for some part of the slot.
     */
    @Query("""
           select a from DoctorAvailability a
           where a.doctor.id = :doctorId and a.isAvailable = false
             and a.startAt < :end and a.endAt > :start
           order by a.startAt asc
           """)
    List<DoctorAvailability> findUnavailableOverlapping(@Param("doctorId") Long doctorId,
                                                        @Param("start") LocalDateTime start,
                                                        @Param("end") LocalDateTime end);

    /**
     * Why this doctor cannot take the slot, or empty when they can. A slot
     * with no end time is checked as a single moment at its start.
     */
    default Optional<String> unavailableReason(Long doctorId, LocalDateTime start, LocalDateTime end) {
        LocalDateTime until = end == null || !end.isAfter(start) ? start.plusMinutes(1) : end;
        return findUnavailableOverlapping(doctorId, start, until).stream()
                .findFirst()
                .map(a -> a.getReason() == null || a.getReason().isBlank()
                        ? "is marked unavailable at that time"
                        : "is marked unavailable at that time (" + a.getReason().strip() + ")");
    }

    /** Whether this doctor is marked available across the whole slot. */
    @Query("""
           select case when count(a) > 0 then true else false end
           from DoctorAvailability a
           where a.doctor.id = :doctorId and a.isAvailable = true
             and a.startAt <= :start and a.endAt >= :end
           """)
    boolean isAvailable(@Param("doctorId") Long doctorId,
                        @Param("start") LocalDateTime start,
                        @Param("end") LocalDateTime end);
}
