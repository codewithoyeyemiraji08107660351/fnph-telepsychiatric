package com.fnph.telepsychiatric.ehr;

import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.patient.Patient;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A genuine patient the snapshot could not match.
 *
 * Worked by the Hub Coordinator, HIM and ICT. A request is a request, not a
 * grant: submitting one creates no account and reveals nothing about whether
 * the claimed number exists.
 *
 * The queue exists because the snapshot is a snapshot. A patient registered
 * last week will not be in a file extracted last month, and refusing them with
 * no route forward would push them back to a physical visit for an
 * administrative reason.
 */
@Entity
@Table(name = "patient_verification_requests")
@Getter
@Setter
public class PatientVerificationRequest extends BaseEntity {

    @Column(name = "ehr_number_claimed", nullable = false, length = 50)
    private String ehrNumberClaimed;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Column(name = "phone_number", nullable = false, length = 20)
    private String phoneNumber;

    @Column(name = "email", length = 100)
    private String email;

    @Column(name = "preferred_contact", nullable = false, length = 20)
    private String preferredContact = "SMS";

    @Column(name = "supporting_note", columnDefinition = "TEXT")
    private String supportingNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private VerificationRequestStatus status = VerificationRequestStatus.SUBMITTED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to_id")
    private Users assignedTo;

    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resulting_patient_id")
    private Patient resultingPatient;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;
}
