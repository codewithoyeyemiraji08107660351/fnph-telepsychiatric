package com.fnph.telepsychiatric.consultation;

import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.storage.StorageArea;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * An assistive draft, never a clinical record on its own.
 *
 * The specification is explicit: transcripts and summaries are assistive drafts
 * and a clinician must review anything retained clinically. Automated diagnosis
 * or treatment recommendation is prohibited outright.
 *
 * {@code retainedClinically} can only become true after a named clinician has
 * reviewed it, which is what keeps a machine transcript of a psychiatric
 * consultation out of the record unless someone qualified has read it.
 */
@Entity
@Table(name = "transcripts")
@Getter
@Setter
public class Transcript extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consultation_id")
    private Consultation consultation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_consultation_id")
    private CentreConsultation centreConsultation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recording_id")
    private Recording recording;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_area", length = 40)
    private StorageArea storageArea;

    @Column(name = "storage_path", length = 500)
    private String storagePath;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "DRAFT";

    @Column(name = "clinician_reviewed_at")
    private LocalDateTime clinicianReviewedAt;

    @Column(name = "clinician_reviewed_by", length = 100)
    private String clinicianReviewedBy;

    @Column(name = "retained_clinically", nullable = false)
    private Boolean retainedClinically = false;
}
