package com.fnph.telepsychiatric.scheduling;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AppointmentStatusHistoryRepository
        extends JpaRepository<AppointmentStatusHistory, Long> {

    List<AppointmentStatusHistory> findAllByAppointmentIdOrderByChangedAtAsc(Long appointmentId);
}
