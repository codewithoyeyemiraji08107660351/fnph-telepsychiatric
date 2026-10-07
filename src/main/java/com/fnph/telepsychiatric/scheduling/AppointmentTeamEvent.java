package com.fnph.telepsychiatric.scheduling;

import com.fnph.telepsychiatric.common.ImmutableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One role on one appointment changing hands. Insert-only.
 *
 * Names are copied at the time so the history still reads correctly after an
 * account is renamed or deactivated.
 */
@Entity
@Table(name = "appointment_team_events")
@Getter
@Setter
public class AppointmentTeamEvent extends ImmutableEntity {

    public enum TeamRole { DOCTOR, NURSE, PHARMACIST, LABORATORY, HIM, ROOM }

    public enum ChangeSource { APPROVAL, ASSIGNMENT, ROOM_CHANGE, RESCHEDULE, BACKFILL, REASSIGNMENT }

    @Column(name = "appointment_id", nullable = false, updatable = false)
    private Long appointmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "team_role", nullable = false, length = 20, updatable = false)
    private TeamRole teamRole;

    @Column(name = "from_ref_id", updatable = false)
    private Long fromRefId;

    @Column(name = "to_ref_id", updatable = false)
    private Long toRefId;

    @Column(name = "from_label", length = 150, updatable = false)
    private String fromLabel;

    @Column(name = "to_label", length = 150, updatable = false)
    private String toLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_source", nullable = false, length = 20, updatable = false)
    private ChangeSource changeSource;

    @Column(name = "reason", length = 500, updatable = false)
    private String reason;

    @Column(name = "changed_by", nullable = false, length = 255, updatable = false)
    private String changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;
}
