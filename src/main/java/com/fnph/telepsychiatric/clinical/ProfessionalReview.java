package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A pharmacy or laboratory review of one clinical document.
 *
 * <h2>The one-way rule</h2>
 *
 * A submitted review goes to the Hub Coordinator. There is no field pointing
 * back at the doctor and no state that returns it, because either would let a
 * prescription change without the prescriber deciding it.
 *
 * A concern is recorded in {@code queryDetail} and raised in the
 * multidisciplinary team. If it needs a change, the doctor writes a new
 * prescription that supersedes the old one.
 */
@Entity
@Table(name = "professional_reviews")
@Getter
@Setter
public class ProfessionalReview extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "review_type", nullable = false, length = 20)
    private ReviewType reviewType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prescription_id")
    private Prescription prescription;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "investigation_id")
    private Investigation investigation;

    /** The professional the Hub Coordinator assigned at approval. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id")
    private Users reviewer;

    @Column(name = "assigned_at", nullable = false)
    private LocalDateTime assignedAt;

    @Column(name = "opened_at")
    private LocalDateTime openedAt;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 20)
    private ReviewOutcome outcome;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "query_raised", nullable = false)
    private Boolean queryRaised = false;

    @Column(name = "query_detail", columnDefinition = "TEXT")
    private String queryDetail;

    @Column(name = "submitted_to_hub_at")
    private LocalDateTime submittedToHubAt;
}
