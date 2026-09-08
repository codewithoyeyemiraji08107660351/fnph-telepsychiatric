package com.fnph.telepsychiatric.helpdesk;

import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.patient.Patient;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A support request.
 *
 * <h2>Nothing here reaches clinical data</h2>
 *
 * The related references below are strings, not associations. An agent can see
 * that a booking exists and where it got to; they cannot follow it into the
 * consultation note, the prescription or the investigation. That is why they
 * are references rather than foreign keys, and it is deliberate rather than an
 * oversight.
 */
@Entity
@Table(name = "support_tickets")
@Getter
@Setter
public class SupportTicket extends BaseEntity {

    @Column(name = "ticket_number", nullable = false, length = 30)
    private String ticketNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private TicketCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 20)
    private TicketPriority priority = TicketPriority.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TicketStatus status = TicketStatus.OPEN;

    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "raised_by_user_id")
    private Users raisedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id")
    private Center centre;

    /** Reference only. There is no path from here into the consultation. */
    @Column(name = "related_appointment_reference", length = 50)
    private String relatedAppointmentReference;

    @Column(name = "related_payment_reference", length = 50)
    private String relatedPaymentReference;

    @Column(name = "related_document_number", length = 50)
    private String relatedDocumentNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to_id")
    private Users assignedTo;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    /** What actually happened, so the reported figure is measured not asserted. */
    @Column(name = "first_responded_at")
    private LocalDateTime firstRespondedAt;

    @Column(name = "first_response_minutes")
    private Integer firstResponseMinutes;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    @Column(name = "resolution_summary", length = 1000)
    private String resolutionSummary;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "escalated_at")
    private LocalDateTime escalatedAt;

    @Column(name = "escalated_to_role", length = 40)
    private String escalatedToRole;

    @Column(name = "escalation_reason", length = 500)
    private String escalationReason;

    /** The helpdesk may never close one of these itself. */
    public boolean requiresEscalation() {
        return category == TicketCategory.CLINICAL_CONCERN;
    }
}
