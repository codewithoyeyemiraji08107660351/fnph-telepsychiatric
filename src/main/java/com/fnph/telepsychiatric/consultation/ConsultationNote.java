package com.fnph.telepsychiatric.consultation;

import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "consultation_notes")
@Getter
@Setter
public class ConsultationNote extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consultation_id", nullable = false)
    private Consultation consultation;

    @Column(name = "clinical_note", columnDefinition = "LONGTEXT")
    private String clinicalNote;

    /**
     * A signed note is not editable. An amendment is a new version pointing at
     * the one it replaces, so the record shows what was written first and what
     * superseded it. Editing in place destroys exactly the evidence an
     * investigation would want.
     */
    @Column(name = "version", nullable = false)
    private Integer version = 1;

    @Column(name = "supersedes_id")
    private Long supersedesId;

    @Column(name = "superseded_at")
    private java.time.LocalDateTime supersededAt;

    @Column(name = "amendment_reason", length = 500)
    private String amendmentReason;

    @Column(name = "is_authoritative", nullable = false)
    private Boolean isAuthoritative = false;

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
