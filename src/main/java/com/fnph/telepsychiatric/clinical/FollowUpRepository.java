package com.fnph.telepsychiatric.clinical;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FollowUpRepository extends JpaRepository<FollowUp, Long> {

    Optional<FollowUp> findByPublicId(String publicId);
    List<FollowUp> findAllByBundleId(Long bundleId);
    List<FollowUp> findAllByPatientIdOrderByCreatedAtDesc(Long patientId);
}
