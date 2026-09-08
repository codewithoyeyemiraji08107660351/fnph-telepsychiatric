package com.fnph.telepsychiatric.helpdesk;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {

    Optional<SupportTicket> findByPublicId(String publicId);
    Optional<SupportTicket> findByTicketNumber(String ticketNumber);

    List<SupportTicket> findAllByRaisedById(Long userId);

    @Query("""
           select t from SupportTicket t
           where t.status in :statuses
           order by case t.priority
                      when com.fnph.telepsychiatric.helpdesk.TicketPriority.URGENT then 0
                      when com.fnph.telepsychiatric.helpdesk.TicketPriority.HIGH   then 1
                      when com.fnph.telepsychiatric.helpdesk.TicketPriority.NORMAL then 2
                      else 3 end asc,
                    t.createdAt asc
           """)
    Page<SupportTicket> findQueue(@Param("statuses") List<TicketStatus> statuses, Pageable pageable);

    /**
     * Tickets past the first-response target with nobody having replied.
     *
     * The number FNPH is told about is measured from this, not asserted.
     */
    @Query("""
           select t from SupportTicket t
           where t.firstRespondedAt is null and t.createdAt < :before
             and t.status not in (com.fnph.telepsychiatric.helpdesk.TicketStatus.RESOLVED,
                                  com.fnph.telepsychiatric.helpdesk.TicketStatus.CLOSED)
           """)
    List<SupportTicket> findBreachingFirstResponse(@Param("before") LocalDateTime before);

    long countByStatus(TicketStatus status);
}
