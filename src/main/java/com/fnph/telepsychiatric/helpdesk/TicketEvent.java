package com.fnph.telepsychiatric.helpdesk;

import com.fnph.telepsychiatric.common.ImmutableEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Every state change on a ticket. Append-only.
 *
 * "Who escalated this and when" has to survive the ticket being closed, and the
 * current status cannot answer it.
 */
@Entity
@Table(name = "ticket_events")
@Getter
@Setter
public class TicketEvent extends ImmutableEntity {

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    @Column(name = "from_value", length = 40)
    private String fromValue;

    @Column(name = "to_value", length = 40)
    private String toValue;

    @Column(name = "actor", length = 100)
    private String actor;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "detail", length = 500)
    private String detail;
}
