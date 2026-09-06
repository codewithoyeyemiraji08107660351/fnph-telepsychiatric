package com.fnph.telepsychiatric.consultation;

import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "centre_consultation_notes")
@Getter
@Setter
public class CentreConsultationNote extends BaseEntity {

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
}
