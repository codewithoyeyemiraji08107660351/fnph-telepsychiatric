package com.fnph.telepsychiatric.clinical;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VitalsRepository extends JpaRepository<Vitals, Long> {

    Optional<Vitals> findByPublicId(String publicId);

    List<Vitals> findAllByAppointmentIdOrderByMeasuredAtDesc(Long appointmentId);

    List<Vitals> findAllByPatientIdOrderByMeasuredAtDesc(Long patientId);

    @Query("""
       select distinct v.appointment.id
       from Vitals v
       where v.appointment.id in :appointmentIds
       """)
    List<Long> findAppointmentIdsWithVitals(
            @Param("appointmentIds") Collection<Long> appointmentIds);
}
