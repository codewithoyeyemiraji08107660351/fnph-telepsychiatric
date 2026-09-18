package com.fnph.telepsychiatric.patient;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface PatientPortalProfileRepository extends JpaRepository<PatientPortalProfile,Long> {
    Optional<PatientPortalProfile> findByPatientId(Long patientId);
}
