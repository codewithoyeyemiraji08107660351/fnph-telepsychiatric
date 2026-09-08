package com.fnph.telepsychiatric.payment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPublicId(String publicId);
    Optional<Payment> findByReference(String reference);
    Optional<Payment> findByRrr(String rrr);

    List<Payment> findAllByPatientIdOrderByCreatedAtDesc(Long patientId);

    /**
     * A verified payment with no appointment yet.
     *
     * This is what unlocks slot selection. A patient with one of these has paid
     * and has not booked, which is exactly the state the journey leaves them in
     * between step 5 and step 6.
     */
    @Query("""
           select p from Payment p
           where p.patient.id = :patientId
             and p.status = com.fnph.telepsychiatric.payment.PaymentStatus.SUCCESS
             and p.appointment is null
           order by p.verifiedAt asc
           """)
    List<Payment> findUnusedVerifiedPayments(@Param("patientId") Long patientId);

    @Query("""
           select p from Payment p
           where p.status = com.fnph.telepsychiatric.payment.PaymentStatus.PENDING
             and p.initiatedAt < :olderThan
           """)
    List<Payment> findStalePending(@Param("olderThan") LocalDateTime olderThan);

    @Query("""
           select p from Payment p
           where p.createdAt between :from and :to
             and (:status is null or p.status = :status)
           order by p.createdAt desc
           """)
    Page<Payment> search(@Param("from") LocalDateTime from,
                         @Param("to") LocalDateTime to,
                         @Param("status") PaymentStatus status,
                         Pageable pageable);
}
