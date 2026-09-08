package com.fnph.telepsychiatric.payment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReconciliationExceptionRepository
        extends JpaRepository<ReconciliationException, Long> {

    Optional<ReconciliationException> findByPublicId(String publicId);

    Page<ReconciliationException> findAllByResolvedAtIsNullOrderByCreatedAtAsc(Pageable pageable);

    long countByResolvedAtIsNull();
}
