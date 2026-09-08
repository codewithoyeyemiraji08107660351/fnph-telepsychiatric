package com.fnph.telepsychiatric.scheduling;

import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One bookable 30-minute period.
 *
 * The version column is the whole point of this class. Two patients pressing
 * the same time at the same moment is an acceptance-gate test: one must win and
 * the other must get a clear rejection, rather than both being told they have
 * the appointment and one of them discovering otherwise on the day.
 */
@Entity
@Table(name = "slots")
@Getter
@Setter
public class Slot extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "publication_id", nullable = false)
    private SchedulePublication publication;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private SlotState state = SlotState.AVAILABLE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private Room room;

    @Column(name = "blocked_reason", length = 255)
    private String blockedReason;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    public boolean isClaimable() {
        return state == SlotState.AVAILABLE;
    }
}
