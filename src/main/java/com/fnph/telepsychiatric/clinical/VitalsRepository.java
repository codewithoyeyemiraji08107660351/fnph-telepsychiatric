package com.fnph.telepsychiatric.clinical;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VitalsRepository extends JpaRepository<Vitals, Long> {

    Optional<Vitals> findByPublicId(String publicId);

    List<Vitals> findAllByAppointmentIdOrderByMeasuredAtDesc(Long appointmentId);

    List<Vitals> findAllByPatientIdOrderByMeasuredAtDesc(Long patientId);
}
