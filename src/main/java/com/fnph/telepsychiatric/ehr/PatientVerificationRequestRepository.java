package com.fnph.telepsychiatric.ehr;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PatientVerificationRequestRepository
        extends JpaRepository<PatientVerificationRequest, Long> {

    Optional<PatientVerificationRequest> findByPublicId(String publicId);

    Page<PatientVerificationRequest> findAllByStatusOrderByCreatedAtAsc(
            VerificationRequestStatus status, Pageable pageable);

    Page<PatientVerificationRequest> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByStatus(VerificationRequestStatus status);

    boolean existsByEhrNumberClaimedAndStatusIn(String ehrNumber,
                                                java.util.Collection<VerificationRequestStatus> statuses);
}
