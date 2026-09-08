package com.fnph.telepsychiatric.ehr;

import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * One patient row inside a snapshot. The minimum field set and nothing more.
 *
 * Date of birth and phone are stored only as hashes and masked display forms.
 * Matching compares hashes; the portal shows masks. Neither needs the
 * plaintext, and a readable table of EHR numbers with names, dates of birth and
 * phone numbers would be a directory of who is a psychiatric patient here.
 */
@Entity
@Table(name = "ehr_verification_records")
@Getter
@Setter
public class EhrVerificationRecord extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ehr_import_id", nullable = false)
    private EhrVerificationImport ehrImport;

    @Column(name = "ehr_number", nullable = false, length = 50)
    private String ehrNumber;

    /**
     * Readable, because enrolment confirms the name back to the patient after
     * they have already proved they hold the record. Never returned before
     * corroboration succeeds.
     */
    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "date_of_birth_hash", nullable = false, length = 64)
    private String dateOfBirthHash;

    @Column(name = "phone_hash", length = 64)
    private String phoneHash;

    @Column(name = "date_of_birth_masked", nullable = false, length = 20)
    private String dateOfBirthMasked;

    @Column(name = "phone_masked", length = 20)
    private String phoneMasked;

    @Column(name = "clinic", length = 100)
    private String clinic;

    @Column(name = "patient_status", length = 50)
    private String patientStatus;

    @Column(name = "is_active_record", nullable = false)
    private Boolean isActiveRecord = true;
}
