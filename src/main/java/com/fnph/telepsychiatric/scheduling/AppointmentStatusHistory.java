package com.fnph.telepsychiatric.scheduling;

import com.fnph.telepsychiatric.common.ImmutableEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Every status change on an appointment. Append-only.
 *
 * The appointment row holds only the current status. Reconstructing who
 * approved what and when is an acceptance requirement, and a current value
 * cannot answer it: after a rejection nothing remains to say a request was ever
 * made.
 */
@Entity
@Table(name = "appointment_status_history")
@Getter
@Setter
public class AppointmentStatusHistory extends ImmutableEntity {

    @Column(name = "appointment_id")
    private Long appointmentId;

    @Column(name = "centre_appointment_id")
    private Long centreAppointmentId;

    @Column(name = "from_status", length = 30)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 30)
    private String toStatus;

    @Column(name = "changed_by", length = 100)
    private String changedBy;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    @Column(name = "reason", length = 500)
    private String reason;
}
