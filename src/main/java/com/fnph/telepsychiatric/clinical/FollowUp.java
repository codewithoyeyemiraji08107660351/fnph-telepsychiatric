package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.consultation.Consultation;
import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.patient.Patient;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Table(name = "follow_ups")
@Getter
@Setter
public class FollowUp extends BaseEntity {


    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consultation_id", nullable = false)
    private Consultation consultation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @Column(name = "recommendation", columnDefinition = "TEXT", nullable = false)
    private String recommendation;

    @Column(name = "expected_timeframe", length = 50)
    private String expectedTimeframe;

    @Column(name = "recommended_date")
    private LocalDate recommendedDate;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status = Status.RECOMMENDED;

    @Column(name = "scheduled_date")
    private LocalDate scheduledDate;

    @Column(name = "completed_date")
    private LocalDate completedDate;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
