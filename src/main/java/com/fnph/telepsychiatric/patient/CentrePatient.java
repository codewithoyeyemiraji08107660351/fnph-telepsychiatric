package com.fnph.telepsychiatric.patient;

import com.fnph.telepsychiatric.appointment.CentreAppointment;
import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.clinical.CentreVitals;
import com.fnph.telepsychiatric.consultation.CentreConsultation;
import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "centre_patients")
@Getter
@Setter
public class CentrePatient extends BaseEntity {

    @Column(name = "centre_patient_id", nullable = false, length = 50)
    private String centrePatientId;

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

    @Column(name = "referral_reason", columnDefinition = "TEXT")
    private String referralReason;

    @Column(name = "assessment", columnDefinition = "TEXT")
    private String assessment;

    @Column(name = "current_condition", columnDefinition = "TEXT")
    private String currentCondition;

    @Column(name = "relevant_medicines", columnDefinition = "TEXT")
    private String relevantMedicines;

    @Column(name = "fnph_ehr_number", length = 50)
    private String fnphEhrNumber;

    @Column(name = "consent_version", length = 20)
    private String consentVersion;

    @Column(name = "consent_accepted_at")
    private LocalDateTime consentAcceptedAt;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id", nullable = false)
    private Center centre;

    @OneToMany(mappedBy = "centrePatient", cascade = CascadeType.ALL)
    private List<CentreAppointment> appointments = new ArrayList<>();

    @OneToMany(mappedBy = "centrePatient", cascade = CascadeType.ALL)
    private List<CentreConsultation> consultations = new ArrayList<>();

    @OneToMany(mappedBy = "centrePatient", cascade = CascadeType.ALL)
    private List<CentreVitals> vitalsRecords = new ArrayList<>();

}
