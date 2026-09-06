package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.consultation.Consultation;
import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.patient.Patient;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "investigations")
@Getter
@Setter
public class Investigation extends BaseEntity {


    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consultation_id", nullable = false)
    private Consultation consultation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false)
    private Users doctor;

    @Column(name = "issue_number", unique = true, nullable = false, length = 50)
    private String issueNumber;

    @Column(name = "investigation_type", nullable = false, length = 100)
    private String investigationType;

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "instructions", columnDefinition = "TEXT")
    private String instructions;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status = Status.DRAFT;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Column(name = "validity_days", nullable = false)
    private Integer validityDays = 7;

    @Column(name = "download_count", nullable = false)
    private Integer downloadCount = 0;

    @Column(name = "max_downloads", nullable = false)
    private Integer maxDownloads = 1;

    @Column(name = "qr_code")
    private String qrCode;

    @Column(name = "verification_url")
    private String verificationUrl;

    @Column(name = "result_url")
    private String resultUrl;

    @Column(name = "result_notes", columnDefinition = "TEXT")
    private String resultNotes;

    @Column(name = "is_view_only", nullable = false)
    private Boolean isViewOnly = false;

    @Column(name = "reviewed_by")
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_notes", columnDefinition = "TEXT")
    private String reviewNotes;
}
