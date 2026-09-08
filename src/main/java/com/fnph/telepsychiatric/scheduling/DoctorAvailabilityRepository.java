package com.fnph.telepsychiatric.scheduling;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface DoctorAvailabilityRepository extends JpaRepository<DoctorAvailability, Long> {

    List<DoctorAvailability> findAllByServiceDateOrderByStartAtAsc(LocalDate serviceDate);

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
