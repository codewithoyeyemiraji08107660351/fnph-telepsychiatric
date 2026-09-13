package com.fnph.telepsychiatric.ehr;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ContactVerificationRepository extends JpaRepository<ContactVerification, Long> {

    Optional<ContactVerification> findByPublicId(String publicId);

    /**
     * Codes waiting for a member of staff to read out.
     *
     * Assisted enrolment, for a patient with no email on the hospital record.
     * Unverified, not yet released, and still inside its window.
     */
    @Query("""
           select c from ContactVerification c
           where c.deliveryRoute = 'ASSISTED' and c.verifiedAt is null
             and c.invalidatedAt is null and c.expiresAt > :now
           order by c.createdAt asc
           """)
    java.util.List<ContactVerification> findPendingAssisted(@Param("now") LocalDateTime now);

    /** Issuing a new code kills the outstanding one, so an old SMS stops working. */
    @Modifying
    @Query("""
           update ContactVerification c set c.invalidatedAt = :now
           where c.ehrNumber = :ehrNumber and c.verifiedAt is null and c.invalidatedAt is null
           """)
    int invalidateOutstanding(@Param("ehrNumber") String ehrNumber,
                              @Param("now") LocalDateTime now);
}
