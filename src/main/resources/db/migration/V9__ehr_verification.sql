-- =====================================================================
-- V9 - EHR verification
--
-- The offline FNPH EHR stays authoritative in phase one and is not
-- integrated. Patient enrolment therefore matches against a snapshot
-- uploaded by Health Information Management.
--
-- SNAPSHOT, NOT A LINK
--   Staleness is a permanent property of this design, so every screen that
--   reads it shows how old it is, and an exception queue exists for the
--   records it cannot match.
--
-- VERSIONED IMPORTS, NEVER OVERWRITTEN
--   Each upload is a new import; rows belong to it. That gives rollback, a
--   diff between snapshots, and an audit trail that survives a bad file. A
--   malformed row rejects the whole import rather than loading half a
--   patient list nobody can identify.
--
-- WHY DATE OF BIRTH AND PHONE ARE STORED HASHED
--   The portal only ever displays masked forms, and matching only ever
--   compares. Neither needs the plaintext.
--
--   This is the control that matters most in the build. A readable table of
--   EHR numbers with names, dates of birth and phone numbers is a directory
--   of who is a psychiatric patient at this hospital. Hashing means a
--   read-only leak through a backup, a log or a query yields nothing usable.
--
-- WHY MATCHING NEEDS MORE THAN AN EHR NUMBER
--   If a number alone returned a name, anyone could walk the range and
--   confirm that a named individual is a patient here. Enrolment requires
--   the number plus one corroborating field, and every attempt is recorded
--   and rate limited.
-- =====================================================================

CREATE TABLE ehr_verification_imports (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    public_id          VARCHAR(26)  NULL,
    created_at         DATETIME(6)  NOT NULL,
    created_by         VARCHAR(100) NULL,
    updated_at         DATETIME(6)  NULL,
    updated_by         VARCHAR(100) NULL,

    file_name          VARCHAR(255) NOT NULL,
    file_checksum      VARCHAR(64)  NOT NULL,
    file_size_bytes    BIGINT       NOT NULL,

    -- The date the hospital extracted the file, supplied by the uploader.
    -- Not the upload date: a file extracted in June and uploaded in
    -- September is three months stale and every screen must say so.
    source_as_at       DATE         NOT NULL,

    status             VARCHAR(20)  NOT NULL,
    row_count          INT          NOT NULL DEFAULT 0,
    valid_row_count    INT          NOT NULL DEFAULT 0,
    rejected_row_count INT          NOT NULL DEFAULT 0,

    -- Line-numbered errors. An import that fails validation is useless
    -- without telling the uploader which rows and why.
    validation_report  LONGTEXT     NULL,

    uploaded_by_id     BIGINT       NULL,
    uploaded_at        DATETIME(6)  NOT NULL,
    activated_at       DATETIME(6)  NULL,
    activated_by       VARCHAR(100) NULL,
    superseded_at      DATETIME(6)  NULL,
    superseded_by_import_id BIGINT  NULL,

    -- Accounts whose stored name or phone differs from this snapshot.
    -- Surfaced for HIM rather than applied; see patients.drift_flagged.
    drift_detected_count INT        NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uk_ehr_imports_public_id (public_id),
    UNIQUE KEY uk_ehr_imports_checksum (file_checksum),
    KEY idx_ehr_imports_status (status, uploaded_at),
    CONSTRAINT fk_ehr_imports_uploader FOREIGN KEY (uploaded_by_id) REFERENCES users (id),
    CONSTRAINT fk_ehr_imports_superseded FOREIGN KEY (superseded_by_import_id)
        REFERENCES ehr_verification_imports (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- The minimum field set, and nothing beyond it. Anything more is a
-- liability with no function.
CREATE TABLE ehr_verification_records (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    public_id           VARCHAR(26)  NULL,
    created_at          DATETIME(6)  NOT NULL,
    created_by          VARCHAR(100) NULL,
    updated_at          DATETIME(6)  NULL,
    updated_by          VARCHAR(100) NULL,

    ehr_import_id       BIGINT       NOT NULL,
    ehr_number          VARCHAR(50)  NOT NULL,

    -- Full name is stored readable because enrolment must confirm it back to
    -- the patient after they have already proved they hold the record. It is
    -- never returned before corroboration succeeds.
    full_name           VARCHAR(150) NOT NULL,

    date_of_birth_hash  VARCHAR(64)  NOT NULL,
    phone_hash          VARCHAR(64)  NULL,

    -- Only these forms are ever displayed.
    date_of_birth_masked VARCHAR(20) NOT NULL,
    phone_masked        VARCHAR(20)  NULL,

    clinic              VARCHAR(100) NULL,
    patient_status      VARCHAR(50)  NULL,
    is_active_record    BIT(1)       NOT NULL DEFAULT b'1',

    PRIMARY KEY (id),
    UNIQUE KEY uk_ehr_records_public_id (public_id),
    -- Unique per import, never globally: imports are versioned and the
    -- previous snapshot must survive.
    UNIQUE KEY uk_ehr_records_import_number (ehr_import_id, ehr_number),
    KEY idx_ehr_records_number (ehr_number),
    CONSTRAINT fk_ehr_records_import FOREIGN KEY (ehr_import_id)
        REFERENCES ehr_verification_imports (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Every enrolment lookup, successful or not. The defence against walking
-- the EHR number range, and the evidence if someone tries.
CREATE TABLE ehr_lookup_attempts (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    public_id           VARCHAR(26)  NULL,
    created_at          DATETIME(6)  NOT NULL,
    created_by          VARCHAR(100) NULL,

    ehr_number_attempted VARCHAR(50) NOT NULL,
    outcome             VARCHAR(30)  NOT NULL,
    ip_address          VARCHAR(45)  NULL,
    user_agent          VARCHAR(500) NULL,
    attempted_at        DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_ehr_lookup_public_id (public_id),
    KEY idx_ehr_lookup_number (ehr_number_attempted, attempted_at),
    KEY idx_ehr_lookup_ip (ip_address, attempted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- The controlled queue for genuine records the snapshot cannot match.
-- Worked by the Hub Coordinator, HIM and ICT. Fake or non-existent records
-- cannot be activated from here; a request is a request, not a grant.
CREATE TABLE patient_verification_requests (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    public_id           VARCHAR(26)  NULL,
    created_at          DATETIME(6)  NOT NULL,
    created_by          VARCHAR(100) NULL,
    updated_at          DATETIME(6)  NULL,
    updated_by          VARCHAR(100) NULL,

    ehr_number_claimed  VARCHAR(50)  NOT NULL,
    full_name           VARCHAR(150) NOT NULL,
    date_of_birth       DATE         NOT NULL,
    phone_number        VARCHAR(20)  NOT NULL,
    email               VARCHAR(100) NULL,
    preferred_contact   VARCHAR(20)  NOT NULL DEFAULT 'SMS',
    supporting_note     TEXT         NULL,

    status              VARCHAR(30)  NOT NULL DEFAULT 'SUBMITTED',
    assigned_to_id      BIGINT       NULL,
    resolution_notes    TEXT         NULL,
    resolved_at         DATETIME(6)  NULL,
    resolved_by         VARCHAR(100) NULL,
    resulting_patient_id BIGINT      NULL,

    ip_address          VARCHAR(45)  NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_patient_verification_public_id (public_id),
    KEY idx_patient_verification_status (status, created_at),
    KEY idx_patient_verification_number (ehr_number_claimed),
    CONSTRAINT fk_patient_verification_assignee FOREIGN KEY (assigned_to_id) REFERENCES users (id),
    CONSTRAINT fk_patient_verification_patient  FOREIGN KEY (resulting_patient_id) REFERENCES patients (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Proves the contact route reaches the person before the account activates.
CREATE TABLE contact_verifications (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    public_id           VARCHAR(26)  NULL,
    created_at          DATETIME(6)  NOT NULL,
    created_by          VARCHAR(100) NULL,
    updated_at          DATETIME(6)  NULL,
    updated_by          VARCHAR(100) NULL,

    patient_id          BIGINT       NULL,
    ehr_number          VARCHAR(50)  NOT NULL,
    channel             VARCHAR(20)  NOT NULL,
    destination_masked  VARCHAR(50)  NOT NULL,
    code_hash           VARCHAR(64)  NOT NULL,
    expires_at          DATETIME(6)  NOT NULL,
    attempts            INT          NOT NULL DEFAULT 0,
    verified_at         DATETIME(6)  NULL,
    invalidated_at      DATETIME(6)  NULL,
    ip_address          VARCHAR(45)  NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_contact_verifications_public_id (public_id),
    KEY idx_contact_verifications_ehr (ehr_number, verified_at),
    KEY idx_contact_verifications_expiry (expires_at),
    CONSTRAINT fk_contact_verifications_patient FOREIGN KEY (patient_id) REFERENCES patients (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Tie the account back to the snapshot that activated it.
ALTER TABLE patients
    ADD CONSTRAINT fk_patients_source_import
        FOREIGN KEY (source_import_id) REFERENCES ehr_verification_imports (id);

CREATE INDEX idx_patients_drift ON patients (drift_flagged);
