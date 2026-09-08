package com.fnph.telepsychiatric.helpdesk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketEventRepository extends JpaRepository<TicketEvent, Long> {

    List<TicketEvent> findAllByTicketIdOrderByOccurredAtAsc(Long ticketId);
}
