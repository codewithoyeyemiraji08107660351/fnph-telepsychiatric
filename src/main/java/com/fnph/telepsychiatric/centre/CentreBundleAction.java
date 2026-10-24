package com.fnph.telepsychiatric.centre;

import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.tenancy.TenantFilters;
import com.fnph.telepsychiatric.tenancy.TenantOwned;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;

/** What one centre service did with one delivered care bundle. */
@Entity
@Table(name = "centre_bundle_actions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_centre_bundle_actions_receipt_capability",
                columnNames = {"receipt_id", "capability"})
})
@Getter
@Setter
@Filter(name = TenantFilters.CENTRE_TENANT, condition = TenantFilters.CONDITION)
public class CentreBundleAction extends BaseEntity implements TenantOwned {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id", nullable = false)
    private Center centre;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id", nullable = false)
    private CentreBundleReceipt receipt;

    @Enumerated(EnumType.STRING)
    @Column(name = "capability", nullable = false, length = 20)
    private CentreCapability capability;

    @Column(name = "acted_at", nullable = false)
    private LocalDateTime actedAt;

    @Column(name = "acted_by", nullable = false, length = 100)
    private String actedBy;

    @Column(name = "notes", nullable = false, columnDefinition = "TEXT")
    private String notes;

    @Override
    public Long resolveCentreId() {
        return centre == null ? null : centre.getId();
    }
}
