package com.fnph.telepsychiatric.scheduling;

import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * When a doctor is available to be assigned.
 *
 * Checked at assignment rather than at publication. Slots are published before
 * anyone knows who will cover them, and a doctor going on leave after a day is
 * published must not invalidate bookings that already exist; it must stop them
 * being assigned to that doctor.
 */
@Entity
@Table(name = "doctor_availability")
@Getter
@Setter
public class DoctorAvailability extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false)
    private Users doctor;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    @Column(name = "is_available", nullable = false)
    private Boolean isAvailable = true;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "set_by", length = 100)
    private String setBy;
}
