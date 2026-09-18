package com.fnph.telepsychiatric.ehr;

import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "manual_ehr_records")
@Getter
@Setter
public class ManualEhrRecord extends BaseEntity {
    @Version
    @Column(nullable = false)
    private Long version = 0L;
    @Column(name = "ehr_number", nullable = false, unique = true, length = 50)
    private String ehrNumber;
    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;
    @Column(name = "date_of_birth_hash", nullable = false, length = 64)
    private String dateOfBirthHash;
    @Column(name = "date_of_birth_masked", nullable = false, length = 20)
    private String dateOfBirthMasked;
    @Column(name = "date_of_birth_encrypted", nullable = false, length = 512)
    private String dateOfBirthEncrypted;
    @Column(name = "phone_hash", length = 64)
    private String phoneHash;
    @Column(name = "phone_masked", length = 20)
    private String phoneMasked;
    @Column(name = "phone_encrypted", length = 512)
    private String phoneEncrypted;
    @Column(name = "email_hash", length = 64)
    private String emailHash;
    @Column(name = "email_masked", length = 60)
    private String emailMasked;
    @Column(name = "email_encrypted", length = 512)
    private String emailEncrypted;
    @Column(length = 100)
    private String clinic;
    @Column(name = "patient_status", length = 50)
    private String patientStatus;
    @Column(name = "is_active_record", nullable = false)
    private Boolean isActiveRecord = true;
    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;
    @Column(name = "last_synced_by", length = 100)
    private String lastSyncedBy;
    @Column(name = "last_sync_direction", length = 20)
    private String lastSyncDirection;
}
