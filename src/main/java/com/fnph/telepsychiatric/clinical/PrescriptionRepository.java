package com.fnph.telepsychiatric.clinical;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PrescriptionRepository extends JpaRepository<Prescription, Long> {

    Optional<Prescription> findByPublicId(String publicId);
    Optional<Prescription> findByIssueNumber(String issueNumber);
    List<Prescription> findAllByBundleId(Long bundleId);
    List<Prescription> findAllByPatientIdOrderByCreatedAtDesc(Long patientId);
}
