package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.appointment.CentreAppointment;
import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.tenancy.TenantFilters;
import com.fnph.telepsychiatric.tenancy.TenantOwned;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything one consultation produced, released together or not at all.
 *
 * The Hub Coordinator performs an administrative completeness check and
 * releases the whole bundle at once. A partial release sends a patient a
 * prescription while the investigation request is still under review, and they
 * act on half their care plan.
 */
@Entity
@Table(name = "release_bundles")
@FilterDef(name = TenantFilters.CENTRE_TENANT,
           parameters = @ParamDef(name = TenantFilters.CENTRE_ID_PARAM, type = Long.class))
@Filter(name = TenantFilters.CENTRE_TENANT, condition = TenantFilters.CONDITION)
@Getter
@Setter
public class ReleaseBundle extends BaseEntity implements TenantOwned {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id")
    private Appointment appointment;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_appointment_id")
    private CentreAppointment centreAppointment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id")
    private Center centre;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BundleStatus status = BundleStatus.INCOMPLETE;

    @Column(name = "blocked_reason", length = 500)
    private String blockedReason;

    @Column(name = "released_by", length = 100)
    private String releasedBy;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "release_notes", length = 500)
    private String releaseNotes;

    @OneToMany(mappedBy = "bundle", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReleaseBundleComponent> components = new ArrayList<>();

    @Override
    public Long resolveCentreId() {
        return centre == null ? null : centre.getId();
    }
}
