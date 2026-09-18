package com.fnph.telepsychiatric.patient;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface PatientIntakeRepository extends JpaRepository<PatientIntake, Long> {
    Optional<PatientIntake> findFirstByPatientIdAndAppointmentPublicIdIsNullOrderByCreatedAtDesc(Long patientId);
    Optional<PatientIntake> findByPublicIdAndPatientId(String publicId, Long patientId);
    Optional<PatientIntake> findByAppointmentPublicId(String appointmentPublicId);
}
