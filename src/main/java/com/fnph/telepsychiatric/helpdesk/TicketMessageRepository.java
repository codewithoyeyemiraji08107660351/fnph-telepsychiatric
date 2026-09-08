package com.fnph.telepsychiatric.helpdesk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketMessageRepository extends JpaRepository<TicketMessage, Long> {

    List<TicketMessage> findAllByTicketIdOrderBySentAtAsc(Long ticketId);

    /** What the requester sees. Internal notes are excluded in the query. */
    List<TicketMessage> findAllByTicketIdAndIsInternalFalseOrderBySentAtAsc(Long ticketId);
}
