package com.fnph.telepsychiatric.center;

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

/**
 * Whether an optional local role is activated at a centre.
 *
 * Replaces three booleans on the centre row. Activation is a Central
 * Administrator decision taken "after staffing and capability review", so it
 * has to carry who, when and why. A boolean records the answer and destroys
 * the review, and it cannot distinguish a capability turned off because the
 * pharmacist left from one turned off because the centre failed an audit.
 */
@Entity
@Table(name = "centre_capabilities")
@FilterDef(name = TenantFilters.CENTRE_TENANT,
           parameters = @ParamDef(name = TenantFilters.CENTRE_ID_PARAM, type = Long.class))
@Filter(name = TenantFilters.CENTRE_TENANT, condition = TenantFilters.CONDITION)
@Getter
@Setter
public class CentreCapability extends BaseEntity implements TenantOwned {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id", nullable = false)
    private Center centre;

    @Enumerated(EnumType.STRING)
    @Column(name = "capability", nullable = false, length = 30)
    private CapabilityType capability;

    @Column(name = "is_enabled", nullable = false)
    private Boolean isEnabled = false;

    @Column(name = "enabled_at")
    private LocalDateTime enabledAt;

    @Column(name = "enabled_by", length = 100)
    private String enabledBy;

    @Column(name = "enable_reason", length = 500)
    private String enableReason;

    @Column(name = "disabled_at")
    private LocalDateTime disabledAt;

    @Column(name = "disabled_by", length = 100)
    private String disabledBy;

    @Column(name = "disable_reason", length = 500)
    private String disableReason;

    @Override
    public Long resolveCentreId() {
        return centre == null ? null : centre.getId();
    }
}
