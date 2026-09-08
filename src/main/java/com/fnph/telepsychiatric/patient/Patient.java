package com.fnph.telepsychiatric.patient;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.clinical.Vitals;
import com.fnph.telepsychiatric.consultation.Consultation;
import com.fnph.telepsychiatric.common.SoftDeletableEntity;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "patients", uniqueConstraints = {
        @UniqueConstraint(name = "uk_patients_ehr_number", columnNames = "ehr_number")
})
@Getter
@Setter
public class Patient extends SoftDeletableEntity {

    @Column(name = "ehr_number", nullable = false, length = 50)
    private String ehrNumber;

    @Column(name = "first_name", nullable = false, length = 50)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 50)
    private String lastName;

    @Column(name = "middle_name", length = 50)
    private String middleName;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Column(name = "gender", length = 10)
    private String gender;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "is_eligible", nullable = false)
    private Boolean isEligible = false;

    @Column(name = "eligibility_verified_at")
    private LocalDateTime eligibilityVerifiedAt;

    @Column(name = "eligibility_verified_by")
    private String eligibilityVerifiedBy;

    @Column(name = "is_physically_assessed", nullable = false)
    private Boolean isPhysicallyAssessed = false;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @OneToOne(mappedBy = "patient", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Users user;

    @OneToMany(mappedBy = "patient", cascade = CascadeType.ALL)
    private List<Appointment> appointments = new ArrayList<>();

    @OneToMany(mappedBy = "patient", cascade = CascadeType.ALL)
    private List<Consultation> consultations = new ArrayList<>();

    @OneToMany(mappedBy = "patient", cascade = CascadeType.ALL)
    private List<Vitals> vitalsRecords = new ArrayList<>();

    /**
     * The verification import snapshot this account was activated against.
     * Populated by the EHR verification module.
     */
    @Column(name = "source_import_id")
    private Long sourceImportId;

    @Column(name = "contact_verified_at")
    private LocalDateTime contactVerifiedAt;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    /**
     * Set when a later EHR import changes the name or phone on an already
     * active account. Silent rebinding is an account-takeover path, so the
     * change is flagged to Health Information Management instead of applied.
     */
    @Column(name = "drift_flagged", nullable = false)
    private Boolean driftFlagged = false;

    @Column(name = "drift_flagged_at")
    private LocalDateTime driftFlaggedAt;

    @Column(name = "drift_details", columnDefinition = "TEXT")
    private String driftDetails;
}