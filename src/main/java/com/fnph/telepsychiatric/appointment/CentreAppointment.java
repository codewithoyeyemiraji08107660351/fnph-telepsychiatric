package com.fnph.telepsychiatric.appointment;

import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.patient.CentrePatient;
import com.fnph.telepsychiatric.user.Users;
import com.fnph.telepsychiatric.tenancy.TenantFilters;
import com.fnph.telepsychiatric.tenancy.TenantOwned;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "centre_appointments", uniqueConstraints = {
        @UniqueConstraint(name = "uk_centre_appointments_reference", columnNames = "reference")
}, indexes = {
        @Index(name = "idx_centre_appointments_centre", columnList = "centre_id,appointment_date"),
        @Index(name = "idx_centre_appointments_status", columnList = "status")
})
@Getter
@Setter
@FilterDef(name = TenantFilters.CENTRE_TENANT,
        parameters = @ParamDef(name = TenantFilters.CENTRE_ID_PARAM, type = Long.class))
@Filter(name = TenantFilters.CENTRE_TENANT, condition = TenantFilters.CONDITION)
public class CentreAppointment extends BaseEntity implements TenantOwned {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_patient_id", nullable = false)
    private CentrePatient centrePatient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id", nullable = false)
    private Center centre;

    @Column(name = "appointment_date", nullable = false)
    private LocalDateTime appointmentDateTime;

    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes = 30;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING_APPROVAL;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id")
    private Users doctor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pharmacy_id")
    private Users pharmacy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "laboratory_id")
    private Users laboratory;

    @Column(name = "room", length = 50)
    private String room;

    @Column(name = "reference", nullable = false, length = 50)
    private String reference;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "postponed_reason", columnDefinition = "TEXT")
    private String postponedReason;

    @Column(name = "returned_reason", columnDefinition = "TEXT")
    private String returnedReason;

    @Column(name = "join_url")
    private String joinUrl;

    @Column(name = "meeting_id")
    private String meetingId;

    @Column(name = "no_show_reason", columnDefinition = "TEXT")
    private String noShowReason;

    @Column(name = "join_window_opens_at")
    private LocalDateTime joinWindowOpensAt;

    @Column(name = "scheduled_end_at")
    private LocalDateTime scheduledEndAt;

    @Column(name = "no_show_at")
    private LocalDateTime noShowAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    /**
     * Tenant key for isolation enforcement. Null means this row belongs to the
     * FNPH pathway rather than to a centre.
     */
    @Override
    public Long resolveCentreId() {
        return centre == null ? null : centre.getId();
    }
}
