package com.fnph.telepsychiatric.scheduling;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SchedulePublicationRepository extends JpaRepository<SchedulePublication, Long> {

    Optional<SchedulePublication> findByPublicId(String publicId);

    Optional<SchedulePublication> findByAudienceAndServiceDate(ScheduleAudience audience,
                                                               LocalDate serviceDate);

    List<SchedulePublication> findAllByServiceDateBetweenOrderByServiceDateAsc(
            LocalDate from, LocalDate to);
}
