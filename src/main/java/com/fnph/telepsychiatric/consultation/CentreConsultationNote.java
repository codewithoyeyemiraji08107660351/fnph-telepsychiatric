package com.fnph.telepsychiatric.consultation;

import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.center.Center;
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
@Table(name = "centre_consultation_notes")
@Getter
@Setter
@Filter(name = TenantFilters.CENTRE_TENANT, condition = TenantFilters.CONDITION)
public class CentreConsultationNote extends BaseEntity implements TenantOwned {
    /**
     * Denormalised tenant key. Present so tenant isolation can be enforced as a
     * single repository-layer filter rather than a join that a future query
     * might omit. A scoping rule that depends on remembering a join fails open.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id", nullable = false)
    private Center centre;


    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_consultation_id", nullable = false)
    private CentreConsultation centreConsultation;

    @Column(name = "clinical_note", columnDefinition = "LONGTEXT", nullable = false)
    private String clinicalNote;

    @Column(name = "is_signed", nullable = false)
    private Boolean isSigned = false;

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @Column(name = "signed_by")
    private String signedBy;

    @Column(name = "follow_up_recommendation", columnDefinition = "TEXT")
    private String followUpRecommendation;

    @Column(name = "follow_up_timeline", length = 50)
    private String followUpTimeline;

    /**
     * Version and supersession, matching ConsultationNote.
     *
     * The columns were added by V14 for both note tables and mapped on only
     * one. Module 3 makes the centre note the authoritative clinical record, so
     * the pathway that most needs an amendment trail was the one without it.
     */
    @Column(name = "version", nullable = false)
    private Integer version = 1;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supersedes_id")
    private CentreConsultationNote supersedes;

    @Column(name = "superseded_at")
    private LocalDateTime supersededAt;

    @Column(name = "amendment_reason", length = 500)
    private String amendmentReason;

    @Column(name = "supersedes_id")
    private int supersedesId;

    /**
     * Tenant key for isolation enforcement. Null means this row belongs to the
     * FNPH pathway rather than to a centre.
     */
    @Override
    public Long resolveCentreId() {
        return centre == null ? null : centre.getId();
    }
}
