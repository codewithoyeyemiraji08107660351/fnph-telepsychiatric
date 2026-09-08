package com.fnph.telepsychiatric.payment;

import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Every provider callback, stored before it is interpreted.
 *
 * Remita retries. A dropped response, a slow reply or a network blip all
 * produce a second callback for the same transaction. Without a unique
 * constraint on the raw event, the second one unlocks slot selection twice or
 * posts two ledger entries.
 *
 * The unique index on {@code payloadHash} is what makes a repeat delivery a
 * no-op rather than a defect. "Duplicate Remita callbacks produce one payment
 * state" is an acceptance gate, and this table is what passes it.
 *
 * The payload is stored before parsing, so a callback the code cannot
 * understand is still available to look at afterwards rather than being lost
 * in a stack trace.
 */
@Entity
@Table(name = "webhook_inbox")
@Getter
@Setter
public class WebhookInbox extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private WebhookProvider provider;

    @Column(name = "provider_event_id", length = 150)
    private String providerEventId;

    @Column(name = "payload", nullable = false, columnDefinition = "LONGTEXT")
    private String payload;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "signature_valid", nullable = false)
    private Boolean signatureValid = false;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_state", nullable = false, length = 20)
    private WebhookState processingState = WebhookState.RECEIVED;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "attempts", nullable = false)
    private Integer attempts = 0;

    @Column(name = "source_ip", length = 45)
    private String sourceIp;
}
