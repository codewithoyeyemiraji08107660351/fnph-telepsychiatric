package com.fnph.telepsychiatric.clinical;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InvestigationRepository extends JpaRepository<Investigation, Long> {

    Optional<Investigation> findByPublicId(String publicId);
    Optional<Investigation> findByIssueNumber(String issueNumber);
    List<Investigation> findAllByBundleId(Long bundleId);
    List<Investigation> findAllByPatientIdOrderByCreatedAtDesc(Long patientId);
}
