ALTER TABLE ehr_verification_records
    ADD COLUMN phone_encrypted VARCHAR(512) NULL;

CREATE TABLE manual_ehr_records (
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    public_id                VARCHAR(26)  NULL,
    created_at               DATETIME(6)  NOT NULL,
    created_by               VARCHAR(100) NULL,
    updated_at               DATETIME(6)  NULL,
    updated_by               VARCHAR(100) NULL,
    version                  BIGINT       NOT NULL DEFAULT 0,
    ehr_number               VARCHAR(50)  NOT NULL,
    full_name                VARCHAR(150) NOT NULL,
    date_of_birth_hash       VARCHAR(64)  NOT NULL,
    date_of_birth_masked     VARCHAR(20)  NOT NULL,
    date_of_birth_encrypted  VARCHAR(512) NOT NULL,
    phone_hash               VARCHAR(64)  NULL,
    phone_masked             VARCHAR(20)  NULL,
    phone_encrypted          VARCHAR(512) NULL,
    email_hash               VARCHAR(64)  NULL,
    email_masked             VARCHAR(60)  NULL,
    email_encrypted          VARCHAR(512) NULL,
    clinic                   VARCHAR(100) NULL,
    patient_status           VARCHAR(50)  NULL,
    is_active_record         BOOLEAN      NOT NULL DEFAULT TRUE,
    last_synced_at           DATETIME(6)  NULL,
    last_synced_by           VARCHAR(100) NULL,
    last_sync_direction      VARCHAR(20)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_manual_ehr_public_id (public_id),
    UNIQUE KEY uk_manual_ehr_number (ehr_number),
    KEY idx_manual_ehr_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
