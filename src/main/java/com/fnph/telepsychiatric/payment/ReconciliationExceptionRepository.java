package com.fnph.telepsychiatric.payment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReconciliationExceptionRepository
        extends JpaRepository<ReconciliationException, Long> {

    Optional<ReconciliationException> findByPublicId(String publicId);

    Page<ReconciliationException> findAllByResolvedAtIsNullOrderByCreatedAtAsc(Pageable pageable);

    long countByResolvedAtIsNull();

    /** Exceptions raised in a half-open window, resolved or not, oldest first. */
    @Query("""
           select e from ReconciliationException e
           left join fetch e.payment
           where e.createdAt >= :from and e.createdAt < :to
           order by e.createdAt asc, e.id asc
           """)
    List<ReconciliationException> findRaisedBetween(@Param("from") LocalDateTime from,
                                                    @Param("to") LocalDateTime to,
                                                    Pageable pageable);
}
