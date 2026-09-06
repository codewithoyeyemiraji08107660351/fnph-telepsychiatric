package com.fnph.telepsychiatric.patient;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.clinical.Vitals;
import com.fnph.telepsychiatric.consultation.Consultation;
import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "patients")
@Getter
@Setter
public class Patient extends BaseEntity {

    @Column(name = "ehr_number", unique = true, nullable = false, length = 50)
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

    @Column(name = "email", length = 100)
    private String email;

    @Column(name = "address", length = 255)
    private String address;

    @Column(name = "is_eligible", nullable = false)
    private Boolean isEligible = false;

    @Column(name = "eligibility_verified_at")
    private LocalDateTime eligibilityVerifiedAt;

    @Column(name = "eligibility_verified_by")
    private String eligibilityVerifiedBy;

    @Column(name = "consent_version", length = 20)
    private String consentVersion;

    @Column(name = "consent_accepted_at")
    private LocalDateTime consentAcceptedAt;

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

    @Column(name = "emergency_contact_name", length = 100)
    private String emergencyContactName;

    @Column(name = "emergency_contact_phone", length = 20)
    private String emergencyContactPhone;

    @Column(name = "emergency_contact_relation", length = 50)
    private String emergencyContactRelation;

}