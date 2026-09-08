package com.fnph.telepsychiatric.scheduling;

import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.patient.Patient;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A slot reserved while the patient pays.
 *
 * This exists because FNPH confirmed the sequence as choose-then-pay. Without
 * an expiry an abandoned payment would hold a clinic slot indefinitely and the
 * schedule would quietly starve: every time would show as taken while nobody
 * was actually booked.
 */
@Entity
@Table(name = "slot_holds")
@Getter
@Setter
public class SlotHold extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "slot_id", nullable = false)
    private Slot slot;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "held_for_patient_id")
    private Patient heldForPatient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "held_for_centre_id")
    private Center heldForCentre;

    @Column(name = "held_at", nullable = false)
    private LocalDateTime heldAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "release_reason", length = 200)
    private String releaseReason;

    public boolean isActive(LocalDateTime now) {
        return releasedAt == null && expiresAt.isAfter(now);
    }
}
