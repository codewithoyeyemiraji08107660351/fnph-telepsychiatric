package com.fnph.telepsychiatric.consultation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ConsultationRepository extends JpaRepository<Consultation, Long> {

    Optional<Consultation> findByPublicId(String publicId);
    Optional<Consultation> findByAppointmentId(Long appointmentId);
    Optional<Consultation> findByRoomName(String roomName);

    /** Live sessions the clock has to act on: warnings and the hard end. */
    @Query("""
           select c from Consultation c
           where c.endedAt is null and c.scheduledEndAt is not null
             and c.scheduledStartAt <= :now
           """)
    List<Consultation> findRunning(@Param("now") LocalDateTime now);

    /** Rooms still open past their expiry, for cleanup. */
    @Query("""
           select c from Consultation c
           where c.roomName is not null and c.roomDeletedAt is null
             and c.roomExpiresAt <= :now
           """)
    List<Consultation> findRoomsToClose(@Param("now") LocalDateTime now);
}
