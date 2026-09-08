package com.fnph.telepsychiatric.scheduling;

import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * One published consultation day.
 *
 * Slots are generated from this rather than created individually, so a day
 * either exists with a full grid or does not exist at all. A half-published day
 * shows a patient two available times out of sixteen and reads as a fully
 * booked clinic.
 */
@Entity
@Table(name = "schedule_publications")
@Getter
@Setter
public class SchedulePublication extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 20)
    private ScheduleAudience audience;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(name = "window_start", nullable = false)
    private LocalTime windowStart;

    @Column(name = "window_end", nullable = false)
    private LocalTime windowEnd;

    /** Read from configuration per audience, so a centre change cannot move FNPH days. */
    @Column(name = "slot_minutes", nullable = false)
    private Integer slotMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PublicationStatus status = PublicationStatus.DRAFT;

    @Column(name = "published_by", length = 100)
    private String publishedBy;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    @Column(name = "withdraw_reason", length = 500)
    private String withdrawReason;

    @Column(name = "slots_generated", nullable = false)
    private Integer slotsGenerated = 0;
}
