package com.fnph.telepsychiatric.centre;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Tenant-scoped automatically by the repository base class. */
public interface CentreReferralRepository extends JpaRepository<CentreReferral, Long> {

    Optional<CentreReferral> findByPublicId(String publicId);
    Optional<CentreReferral> findByReference(String reference);

    List<CentreReferral> findAllByStatusOrderByCreatedAtAsc(ReferralStatus status);

    /** Every referral for one patient, newest first. The clinical history. */
    List<CentreReferral> findAllByCentrePatientIdOrderByCreatedAtDesc(Long centrePatientId);

    long countByStatus(ReferralStatus status);
}
