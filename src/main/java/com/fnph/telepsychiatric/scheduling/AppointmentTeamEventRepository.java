package com.fnph.telepsychiatric.scheduling;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AppointmentTeamEventRepository extends JpaRepository<AppointmentTeamEvent, Long> {

    /** Newest first. */
    List<AppointmentTeamEvent> findAllByAppointmentIdOrderByChangedAtDescIdDesc(Long appointmentId);
}
