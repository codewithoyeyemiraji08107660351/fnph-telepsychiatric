package com.fnph.telepsychiatric.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WebhookInboxRepository extends JpaRepository<WebhookInbox, Long> {

    Optional<WebhookInbox> findByPayloadHash(String payloadHash);

    boolean existsByPayloadHash(String payloadHash);

    @Query("""
           select w from WebhookInbox w
           where w.processingState = com.fnph.telepsychiatric.payment.WebhookState.FAILED
             and w.attempts < :maxAttempts
           order by w.receivedAt asc
           """)
    List<WebhookInbox> findRetryable(@Param("maxAttempts") int maxAttempts);
}
