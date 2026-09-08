package com.fnph.telepsychiatric.payment;

import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * An intent written in the same transaction as the state change that caused it.
 *
 * Marking a payment verified and unlocking slot selection must both happen or
 * neither. A message published to a queue after the transaction commits can be
 * lost if the process dies in between; one published before it can arrive for a
 * transaction that then rolls back.
 *
 * Writing the intent here, in the same transaction, then publishing from a
 * poller, makes it exactly-once without a distributed transaction. The cost is
 * a small delay; the alternative is a patient who paid and cannot book.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@Setter
public class OutboxEvent extends BaseEntity {

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "payload", columnDefinition = "LONGTEXT")
    private String payload;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "attempts", nullable = false)
    private Integer attempts = 0;

    @Column(name = "last_error", length = 500)
    private String lastError;

    /** Backs off after a failure rather than spinning on a broken consumer. */
    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;
}
