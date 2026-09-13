package com.fnph.telepsychiatric.triage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConsentAcceptanceRepository extends JpaRepository<ConsentAcceptance, Long> {

    Optional<ConsentAcceptance> findByPublicId(String publicId);

    List<ConsentAcceptance> findAllByPatientIdOrderByAcceptedAtDesc(Long patientId);

    Optional<ConsentAcceptance> findFirstByPatientIdOrderByAcceptedAtDesc(Long patientId);
}