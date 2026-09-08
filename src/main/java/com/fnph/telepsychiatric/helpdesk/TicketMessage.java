package com.fnph.telepsychiatric.helpdesk;

import com.fnph.telepsychiatric.common.ImmutableEntity;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One message on a ticket. Append-only.
 *
 * A support conversation that can be edited afterwards is not a record of what
 * was said, and these threads are where a patient describes a problem and a
 * member of staff answers it.
 *
 * Internal notes are never returned to the person who raised the ticket.
 * Without that distinction, staff either write nothing down or write it
 * somewhere outside the system, and both are worse.
 */
@Entity
@Table(name = "ticket_messages")
@Getter
@Setter
public class TicketMessage extends ImmutableEntity {

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_user_id")
    private Users authorUser;

    /** Shown to the requester. Staff appear as a desk, not as a named person. */
    @Column(name = "author_label", nullable = false, length = 100)
    private String authorLabel;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "is_internal", nullable = false)
    private Boolean isInternal = false;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;
}
