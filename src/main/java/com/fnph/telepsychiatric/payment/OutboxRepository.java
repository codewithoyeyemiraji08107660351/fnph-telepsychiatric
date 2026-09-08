package com.fnph.telepsychiatric.payment;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    @Query("""
           select e from OutboxEvent e
           where e.publishedAt is null
             and (e.nextAttemptAt is null or e.nextAttemptAt <= :now)
           order by e.id asc
           """)
    List<OutboxEvent> findUnpublished(@Param("now") LocalDateTime now, Pageable pageable);
}
