package com.fnph.telepsychiatric.helpdesk;

import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.configuration.ConfigurationKeys;
import com.fnph.telepsychiatric.configuration.ConfigurationService;
import com.fnph.telepsychiatric.notification.InAppNotificationService;
import com.fnph.telepsychiatric.notification.NotificationType;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.security.crypto.Tokens;
import com.fnph.telepsychiatric.user.UserRepository;
import com.fnph.telepsychiatric.user.Users;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The helpdesk.
 *
 * <h2>The risk this module creates, and what answers it</h2>
 *
 * A free-text ticket thread is the easiest place in this system for clinical
 * information to end up where it should not be. A patient types their symptoms
 * into a support form, an agent with no clinical permission reads them, and
 * every boundary in the permission matrix has been walked around by a text box.
 *
 * Three things answer that. A ticket categorised {@code CLINICAL_CONCERN}
 * cannot be resolved here and is escalated automatically with the emergency
 * guidance attached. Internal notes are never returned to a requester. And
 * nothing in this service reads a clinical table: related bookings, payments
 * and documents are referenced by identifier, so an agent can see that a
 * booking exists without following it into the consultation.
 *
 * <h2>A clinical concern is escalated, not prioritised</h2>
 *
 * Raising its priority would leave it with the helpdesk and make them answer
 * faster. It has to leave the helpdesk entirely.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HelpdeskService {

    private final SupportTicketRepository ticketRepository;
    private final TicketMessageRepository messageRepository;
    private final TicketEventRepository eventRepository;
    private final UserRepository userRepository;
    private final ConfigurationService configuration;
    private final InAppNotificationService notifications;
    private final AuditService auditService;

    @Transactional
    public SupportTicket raise(TicketCategory category, String subject, String body,
                               String appointmentReference, String paymentReference,
                               String documentNumber) {

        Users raiser = CurrentUser.get()
                .flatMap(p -> userRepository.findById(p.getUserId()))
                .orElse(null);

        SupportTicket ticket = new SupportTicket();
        ticket.setTicketNumber("TKT-" + Tokens.generateRecoveryCode().replace("-", ""));
        ticket.setCategory(category);
        ticket.setSubject(subject);
        ticket.setRaisedBy(raiser);
        ticket.setRelatedAppointmentReference(appointmentReference);
        ticket.setRelatedPaymentReference(paymentReference);
        ticket.setRelatedDocumentNumber(documentNumber);
        ticket.setStatus(TicketStatus.OPEN);
        if (raiser != null && raiser.getPatient() != null) {
            ticket.setPatient(raiser.getPatient());
        }
        SupportTicket saved = ticketRepository.save(ticket);

        addMessage(saved.getId(), body, false,
                raiser == null ? "Requester" : raiser.getFirstName(), raiser);
        record(saved.getId(), "RAISED", null, TicketStatus.OPEN.name(), null);

        if (saved.requiresEscalation()) {
            // Automatic, and immediate. A support agent has no clinical
            // permission and no clinical training, and answering would put
            // them in the position of giving medical advice.
            escalate(saved, configuration.getString(
                            ConfigurationKeys.HELPDESK_CLINICAL_ESCALATION_ROLE),
                    "Clinical concern. The helpdesk does not answer these.");

            addMessage(saved.getId(), emergencyGuidance(), false, "FNPH Kaduna", null);
        } else {
            notifications.notifyRole("HELPDESK", null, NotificationType.SUPPORT_TICKET_UPDATE,
                    "New support ticket",
                    "%s: %s".formatted(saved.getCategory().name(), saved.getSubject()),
                    "/helpdesk/tickets/" + saved.getPublicId(),
                    "SupportTicket", saved.getId());
        }

        log.info("Ticket {} raised: {}", saved.getTicketNumber(), category);
        return saved;
    }

    /**
     * The guidance a patient with a clinical concern is given immediately.
     *
     * Sent without waiting for an agent, because the whole point is that
     * somebody worried about their health at 11pm should not sit in a queue
     * before being told this service is not for emergencies.
     */
    private String emergencyGuidance() {
        return """
               This service does not handle medical emergencies and cannot give medical \
               advice through support.

               If this is an emergency, or if there is any immediate risk, go to the \
               nearest emergency department now or call %s.

               Your message has been passed to the clinical coordination team. If it is \
               about your treatment, they will arrange the right next step, which may be a \
               follow-up appointment."""
                .formatted(configuration.getString(ConfigurationKeys.CLINICAL_EMERGENCY_NUMBER));
    }

    /**
     * Replies to the requester and records the first response time.
     *
     * The ten-minute target is configuration; this records what actually
     * happened, so the figure reported to FNPH is measured rather than asserted.
     */
    @Transactional
    public TicketMessage reply(String ticketPublicId, String body, boolean internal) {
        SupportTicket ticket = require(ticketPublicId);

        if (ticket.requiresEscalation() && !internal) {
            throw new IllegalStateException(
                    "This is a clinical concern and has been escalated. The helpdesk does not "
                            + "reply to these; the clinical coordination team does. Add an "
                            + "internal note instead if you have context to pass on.");
        }

        Users agent = CurrentUser.get()
                .flatMap(p -> userRepository.findById(p.getUserId()))
                .orElse(null);

        LocalDateTime now = LocalDateTime.now();
        if (!internal && ticket.getFirstRespondedAt() == null) {
            ticket.setFirstRespondedAt(now);
            ticket.setFirstResponseMinutes(
                    (int) Duration.between(ticket.getCreatedAt(), now).toMinutes());
            ticketRepository.save(ticket);
        }
        if (ticket.getStatus() == TicketStatus.OPEN) {
            transition(ticket, TicketStatus.IN_PROGRESS, null);
        }

        // Staff appear as a desk, not as a named individual. A patient does not
        // need a support agent's name and the agent does not need to be
        // findable by one.
        return addMessage(ticket.getId(), body, internal,
                internal ? (agent == null ? "Staff" : agent.getUsername()) : "FNPH Support",
                agent);
    }

    /** Moves a ticket to another team. Recorded, with a reason. */
    @Transactional
    public SupportTicket escalate(String ticketPublicId, String toRole, String reason) {
        return escalate(require(ticketPublicId), toRole, reason);
    }

    private SupportTicket escalate(SupportTicket ticket, String toRole, String reason) {
        LocalDateTime now = LocalDateTime.now();
        ticket.setEscalatedAt(now);
        ticket.setEscalatedToRole(toRole);
        ticket.setEscalationReason(reason);
        ticket.setStatus(TicketStatus.ESCALATED);
        ticketRepository.save(ticket);

        record(ticket.getId(), "ESCALATED", null, toRole, reason);

        notifications.notifyRole(toRole, ticket.getCentre(),
                NotificationType.SUPPORT_TICKET_UPDATE,
                "Escalated support ticket",
                "%s: %s".formatted(ticket.getCategory().name(), ticket.getSubject()),
                "/tickets/" + ticket.getPublicId(), "SupportTicket", ticket.getId());

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.RECORD_UPDATED)
                .entityType("SupportTicket")
                .entityId(ticket.getId())
                .details("Escalated to " + toRole)
                .reason(reason)
                .build());

        log.info("Ticket {} escalated to {}", ticket.getTicketNumber(), toRole);
        return ticket;
    }

    /**
     * Resolves a ticket.
     *
     * A clinical concern cannot be resolved by the helpdesk. It stays with the
     * team it was escalated to, and only a holder of their permission closes it.
     */
    @Transactional
    public SupportTicket resolve(String ticketPublicId, String summary) {
        SupportTicket ticket = require(ticketPublicId);

        if (ticket.requiresEscalation() && !CurrentUser.get()
                .map(p -> p.getPermissions().contains("ticket.escalate"))
                .orElse(false)) {
            throw new IllegalStateException(
                    "A clinical concern is resolved by the team it was escalated to, not by "
                            + "the helpdesk.");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException(
                    "Say what was done. A ticket closed with no summary tells the next person "
                            + "who sees the same problem nothing.");
        }

        LocalDateTime now = LocalDateTime.now();
        ticket.setStatus(TicketStatus.RESOLVED);
        ticket.setResolvedAt(now);
        ticket.setResolvedBy(CurrentUser.usernameOrSystem());
        ticket.setResolutionSummary(summary);
        ticketRepository.save(ticket);

        record(ticket.getId(), "RESOLVED", null, TicketStatus.RESOLVED.name(), summary);

        if (ticket.getRaisedBy() != null) {
            notifications.notifyUser(ticket.getRaisedBy(),
                    NotificationType.SUPPORT_TICKET_UPDATE,
                    "Your support request has been resolved",
                    "Ticket " + ticket.getTicketNumber() + " has been resolved.",
                    "/support/tickets/" + ticket.getPublicId(),
                    "SupportTicket", ticket.getId());
        }
        return ticket;
    }

    @Transactional(readOnly = true)
    public List<TicketMessage> threadFor(String ticketPublicId, boolean includeInternal) {
        SupportTicket ticket = require(ticketPublicId);
        return includeInternal
                ? messageRepository.findAllByTicketIdOrderBySentAtAsc(ticket.getId())
                : messageRepository.findAllByTicketIdAndIsInternalFalseOrderBySentAtAsc(
                        ticket.getId());
    }

    /** Tickets past the first-response target. Measured, not asserted. */
    @Transactional(readOnly = true)
    public List<SupportTicket> breachingFirstResponse() {
        int target = configuration.getInt(ConfigurationKeys.HELPDESK_FIRST_RESPONSE_MINUTES);
        return ticketRepository.findBreachingFirstResponse(
                LocalDateTime.now().minusMinutes(target));
    }

    // -----------------------------------------------------------------

    private TicketMessage addMessage(Long ticketId, String body, boolean internal,
                                     String authorLabel, Users author) {
        TicketMessage message = new TicketMessage();
        message.setTicketId(ticketId);
        message.setBody(body);
        message.setIsInternal(internal);
        message.setAuthorLabel(authorLabel);
        message.setAuthorUser(author);
        message.setSentAt(LocalDateTime.now());
        return messageRepository.save(message);
    }

    private void transition(SupportTicket ticket, TicketStatus to, String detail) {
        String from = ticket.getStatus().name();
        ticket.setStatus(to);
        ticketRepository.save(ticket);
        record(ticket.getId(), "STATUS_CHANGED", from, to.name(), detail);
    }

    private void record(Long ticketId, String type, String from, String to, String detail) {
        TicketEvent event = new TicketEvent();
        event.setTicketId(ticketId);
        event.setEventType(type);
        event.setFromValue(from);
        event.setToValue(to);
        event.setActor(CurrentUser.usernameOrSystem());
        event.setOccurredAt(LocalDateTime.now());
        event.setDetail(detail);
        eventRepository.save(event);
    }

    private SupportTicket require(String publicId) {
        return ticketRepository.findByPublicId(publicId)
                .orElseThrow(() -> new IllegalArgumentException("No such ticket"));
    }
}
