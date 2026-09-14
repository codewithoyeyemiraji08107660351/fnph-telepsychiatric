package com.fnph.telepsychiatric.helpdesk;

import com.fnph.telepsychiatric.tenancy.UnscopedQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Support tickets. Raised by anyone, worked by ICT.
 *
 * <h2>Raising and working are different populations</h2>
 *
 * A centre raises a ticket and reads its own through
 * {@link #findAllByRaisedById}, keyed by the authenticated user. Every other
 * read here is ICT working the queue across all 23 centres, guarded by
 * {@code ticket.read}, {@code ticket.respond}, {@code ticket.assign},
 * {@code ticket.escalate} or {@code ticket.close}, none of which a centre role
 * holds.
 *
 * That split is the whole justification, so it is the thing to re-check if the
 * role matrix changes. If a centre role is ever given {@code ticket.read},
 * {@link #findQueue} becomes a cross-centre read of other centres' reported
 * problems, and ticket bodies routinely name patients.
 */
public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {

    @UnscopedQuery(value = UnscopedQuery.Reason.HOSPITAL_QUEUE,
            detail = "reached only from reply, assign, escalate and resolve, all ICT "
                    + "permissions; the raiser reads their own via findAllByRaisedById")
    Optional<SupportTicket> findByPublicId(String publicId);

    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "userId is CurrentUser.require().getUserId() for GET /tickets/mine, "
                    + "so the caller can only name themselves")
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
    @UnscopedQuery(value = UnscopedQuery.Reason.HOSPITAL_QUEUE,
            detail = "the ICT work queue spans every centre by design; guarded by ticket.read")
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
    @UnscopedQuery(value = UnscopedQuery.Reason.HOSPITAL_QUEUE,
            detail = "SLA breach report is an FNPH-wide measure; a per-centre version would "
                    + "not be the number being reported")
    List<SupportTicket> findBreachingFirstResponse(@Param("before") LocalDateTime before);
}