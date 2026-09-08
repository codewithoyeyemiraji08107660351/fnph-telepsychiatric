-- =====================================================================
-- FNPH Kaduna Telepsychiatry — baseline schema
--
-- Replaces hibernate ddl-auto:update. Hibernate now runs in validate mode
-- and this file is the single source of truth for the schema.
--
-- Conventions:
--   * All timestamps are UTC. Conversion to WAT happens in the clients.
--   * Booleans are BIT(1), which is what Hibernate maps java.lang.Boolean to
--     on MySQL. Using TINYINT here would fail schema validation.
--   * audit_logs and wallet_transactions are append-only. See V2 for grants.
-- =====================================================================

SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- ---------------------------------------------------------------------
-- Centres (tenants)
-- ---------------------------------------------------------------------
CREATE TABLE centres (
    id                            BIGINT       NOT NULL AUTO_INCREMENT,
    created_at                    DATETIME(6)  NOT NULL,
    updated_at                    DATETIME(6)  NULL,
    deleted                       BIT(1)       NOT NULL DEFAULT b'0',
    deleted_at                    DATETIME(6)  NULL,
    deleted_by                    VARCHAR(100) NULL,
    deleted_reason                VARCHAR(500) NULL,
    code                          VARCHAR(20)  NOT NULL,
    name                          VARCHAR(100) NOT NULL,
    lga                           VARCHAR(50)  NULL,
    state                         VARCHAR(50)  NULL,
    address                       VARCHAR(255) NULL,
    phone_number                  VARCHAR(20)  NULL,
    email                         VARCHAR(100) NULL,
    is_active                     BIT(1)       NOT NULL DEFAULT b'1',
    created_by                    VARCHAR(255) NULL,
    has_pharmacy_capability       BIT(1)       NOT NULL DEFAULT b'0',
    has_laboratory_capability     BIT(1)       NOT NULL DEFAULT b'0',
    has_him_capability            BIT(1)       NOT NULL DEFAULT b'0',
    default_consultation_minutes  INT          NOT NULL DEFAULT 30,
    PRIMARY KEY (id),
    UNIQUE KEY uk_centres_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Patients (FNPH pathway). Minimum field set only.
-- ---------------------------------------------------------------------
CREATE TABLE patients (
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NULL,
    deleted                  BIT(1)       NOT NULL DEFAULT b'0',
    deleted_at               DATETIME(6)  NULL,
    deleted_by               VARCHAR(100) NULL,
    deleted_reason           VARCHAR(500) NULL,
    ehr_number               VARCHAR(50)  NOT NULL,
    first_name               VARCHAR(50)  NOT NULL,
    last_name                VARCHAR(50)  NOT NULL,
    middle_name              VARCHAR(50)  NULL,
    date_of_birth            DATE         NOT NULL,
    gender                   VARCHAR(10)  NULL,
    phone_number             VARCHAR(20)  NULL,
    is_eligible              BIT(1)       NOT NULL DEFAULT b'0',
    eligibility_verified_at  DATETIME(6)  NULL,
    eligibility_verified_by  VARCHAR(255) NULL,
    is_physically_assessed   BIT(1)       NOT NULL DEFAULT b'0',
    is_active                BIT(1)       NOT NULL DEFAULT b'1',
    source_import_id         BIGINT       NULL,
    contact_verified_at      DATETIME(6)  NULL,
    activated_at             DATETIME(6)  NULL,
    drift_flagged            BIT(1)       NOT NULL DEFAULT b'0',
    drift_flagged_at         DATETIME(6)  NULL,
    drift_details            TEXT         NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_patients_ehr_number (ehr_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Users. centre_id is populated for centre staff only; patient_id for
-- patient accounts only. Both null for FNPH staff.
-- ---------------------------------------------------------------------
CREATE TABLE users (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NULL,
    deleted               BIT(1)       NOT NULL DEFAULT b'0',
    deleted_at            DATETIME(6)  NULL,
    deleted_by            VARCHAR(100) NULL,
    deleted_reason        VARCHAR(500) NULL,
    username              VARCHAR(100) NOT NULL,
    email                 VARCHAR(100) NOT NULL,
    password              VARCHAR(255) NOT NULL,
    first_name            VARCHAR(50)  NOT NULL,
    last_name             VARCHAR(50)  NOT NULL,
    phone_number          VARCHAR(20)  NULL,
    staff_number          VARCHAR(50)  NULL,
    role                  VARCHAR(50)  NOT NULL,
    is_active             BIT(1)       NOT NULL DEFAULT b'1',
    mfa_enabled           BIT(1)       NOT NULL DEFAULT b'0',
    must_change_password  BIT(1)       NOT NULL DEFAULT b'0',
    last_login_at         DATETIME(6)  NULL,
    failed_login_attempts INT          NOT NULL DEFAULT 0,
    account_locked        BIT(1)       NOT NULL DEFAULT b'0',
    lock_expiry           DATETIME(6)  NULL,
    password_changed_at   DATETIME(6)  NULL,
    activated_at          DATETIME(6)  NULL,
    deactivated_at        DATETIME(6)  NULL,
    deactivated_reason    VARCHAR(500) NULL,
    login_type            VARCHAR(20)  NOT NULL DEFAULT 'USERNAME',
    centre_id             BIGINT       NULL,
    patient_id            BIGINT       NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username),
    UNIQUE KEY uk_users_email (email),
    KEY idx_users_role (role),
    KEY idx_users_centre (centre_id),
    CONSTRAINT fk_users_centre  FOREIGN KEY (centre_id)  REFERENCES centres (id),
    CONSTRAINT fk_users_patient FOREIGN KEY (patient_id) REFERENCES patients (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Centre staff assignment
-- ---------------------------------------------------------------------
CREATE TABLE centre_staff (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NULL,
    centre_id     BIGINT       NOT NULL,
    user_id       BIGINT       NOT NULL,
    role          VARCHAR(50)  NOT NULL,
    is_primary    BIT(1)       NOT NULL DEFAULT b'0',
    is_active     BIT(1)       NOT NULL DEFAULT b'1',
    activated_at  DATETIME(6)  NULL,
    activated_by  VARCHAR(255) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_centre_staff_centre_user (centre_id, user_id),
    CONSTRAINT fk_centre_staff_centre FOREIGN KEY (centre_id) REFERENCES centres (id),
    CONSTRAINT fk_centre_staff_user   FOREIGN KEY (user_id)   REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Centre patients. Tenant-scoped. fnph_ehr_number is narrative only and
-- must never be joined to patients.ehr_number.
-- ---------------------------------------------------------------------
CREATE TABLE centre_patients (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NULL,
    deleted              BIT(1)       NOT NULL DEFAULT b'0',
    deleted_at           DATETIME(6)  NULL,
    deleted_by           VARCHAR(100) NULL,
    deleted_reason       VARCHAR(500) NULL,
    centre_id            BIGINT       NOT NULL,
    centre_patient_id    VARCHAR(50)  NOT NULL,
    first_name           VARCHAR(50)  NOT NULL,
    last_name            VARCHAR(50)  NOT NULL,
    middle_name          VARCHAR(50)  NULL,
    date_of_birth        DATE         NOT NULL,
    gender               VARCHAR(10)  NULL,
    phone_number         VARCHAR(20)  NULL,
    email                VARCHAR(100) NULL,
    address              VARCHAR(255) NULL,
    referral_reason      TEXT         NULL,
    assessment           TEXT         NULL,
    current_condition    TEXT         NULL,
    relevant_medicines   TEXT         NULL,
    fnph_ehr_number      VARCHAR(50)  NULL,
    consent_version      VARCHAR(20)  NULL,
    consent_accepted_at  DATETIME(6)  NULL,
    is_active            BIT(1)       NOT NULL DEFAULT b'1',
    PRIMARY KEY (id),
    UNIQUE KEY uk_centre_patients_centre_local_id (centre_id, centre_patient_id),
    KEY idx_centre_patients_centre (centre_id),
    CONSTRAINT fk_centre_patients_centre FOREIGN KEY (centre_id) REFERENCES centres (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Appointments (FNPH pathway)
-- ---------------------------------------------------------------------
CREATE TABLE appointments (
    id                        BIGINT       NOT NULL AUTO_INCREMENT,
    created_at                DATETIME(6)  NOT NULL,
    updated_at                DATETIME(6)  NULL,
    patient_id                BIGINT       NOT NULL,
    reference                 VARCHAR(50)  NOT NULL,
    appointment_date          DATETIME(6)  NOT NULL,
    duration_minutes          INT          NOT NULL DEFAULT 30,
    status                    VARCHAR(30)  NOT NULL,
    doctor_id                 BIGINT       NULL,
    pharmacist_id             BIGINT       NULL,
    laboratory_technician_id  BIGINT       NULL,
    nurse_id                  BIGINT       NULL,
    him_officer_id            BIGINT       NULL,
    room                      VARCHAR(50)  NULL,
    reason                    TEXT         NULL,
    notes                     TEXT         NULL,
    approved_by               VARCHAR(255) NULL,
    approved_at               DATETIME(6)  NULL,
    rejected_reason           TEXT         NULL,
    join_url                  VARCHAR(255) NULL,
    meeting_id                VARCHAR(255) NULL,
    no_show_reason            TEXT         NULL,
    cancelled_at              DATETIME(6)  NULL,
    cancelled_by              VARCHAR(255) NULL,
    cancellation_reason       TEXT         NULL,
    nursing_completed_at      DATETIME(6)  NULL,
    him_completed_at          DATETIME(6)  NULL,
    join_window_opens_at      DATETIME(6)  NULL,
    scheduled_end_at          DATETIME(6)  NULL,
    no_show_at                DATETIME(6)  NULL,
    version                   BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_appointments_reference (reference),
    KEY idx_appointments_status (status),
    KEY idx_appointments_datetime (appointment_date),
    KEY idx_appointments_doctor (doctor_id, appointment_date),
    CONSTRAINT fk_appointments_patient    FOREIGN KEY (patient_id)               REFERENCES patients (id),
    CONSTRAINT fk_appointments_doctor     FOREIGN KEY (doctor_id)                REFERENCES users (id),
    CONSTRAINT fk_appointments_pharmacist FOREIGN KEY (pharmacist_id)            REFERENCES users (id),
    CONSTRAINT fk_appointments_lab        FOREIGN KEY (laboratory_technician_id) REFERENCES users (id),
    CONSTRAINT fk_appointments_nurse      FOREIGN KEY (nurse_id)                 REFERENCES users (id),
    CONSTRAINT fk_appointments_him        FOREIGN KEY (him_officer_id)           REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Centre appointments
-- ---------------------------------------------------------------------
CREATE TABLE centre_appointments (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NULL,
    centre_id             BIGINT       NOT NULL,
    centre_patient_id     BIGINT       NOT NULL,
    reference             VARCHAR(50)  NOT NULL,
    appointment_date      DATETIME(6)  NOT NULL,
    duration_minutes      INT          NOT NULL DEFAULT 30,
    status                VARCHAR(30)  NOT NULL,
    doctor_id             BIGINT       NULL,
    pharmacy_id           BIGINT       NULL,
    laboratory_id         BIGINT       NULL,
    room                  VARCHAR(50)  NULL,
    approved_by           VARCHAR(255) NULL,
    approved_at           DATETIME(6)  NULL,
    postponed_reason      TEXT         NULL,
    returned_reason       TEXT         NULL,
    join_url              VARCHAR(255) NULL,
    meeting_id            VARCHAR(255) NULL,
    no_show_reason        TEXT         NULL,
    join_window_opens_at  DATETIME(6)  NULL,
    scheduled_end_at      DATETIME(6)  NULL,
    no_show_at            DATETIME(6)  NULL,
    version               BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_centre_appointments_reference (reference),
    KEY idx_centre_appointments_centre (centre_id, appointment_date),
    KEY idx_centre_appointments_status (status),
    CONSTRAINT fk_centre_appt_centre  FOREIGN KEY (centre_id)         REFERENCES centres (id),
    CONSTRAINT fk_centre_appt_patient FOREIGN KEY (centre_patient_id) REFERENCES centre_patients (id),
    CONSTRAINT fk_centre_appt_doctor  FOREIGN KEY (doctor_id)         REFERENCES users (id),
    CONSTRAINT fk_centre_appt_pharm   FOREIGN KEY (pharmacy_id)       REFERENCES users (id),
    CONSTRAINT fk_centre_appt_lab     FOREIGN KEY (laboratory_id)     REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Vitals
-- ---------------------------------------------------------------------
CREATE TABLE vitals (
    id                        BIGINT       NOT NULL AUTO_INCREMENT,
    created_at                DATETIME(6)  NOT NULL,
    updated_at                DATETIME(6)  NULL,
    patient_id                BIGINT       NOT NULL,
    appointment_id            BIGINT       NULL,
    blood_pressure_systolic   INT          NULL,
    blood_pressure_diastolic  INT          NULL,
    heart_rate                INT          NULL,
    respiratory_rate          INT          NULL,
    temperature               DOUBLE       NULL,
    weight_kg                 DOUBLE       NULL,
    height_cm                 DOUBLE       NULL,
    bmi                       DOUBLE       NULL,
    blood_oxygen              INT          NULL,
    blood_glucose             DOUBLE       NULL,
    measured_at               DATETIME(6)  NOT NULL,
    measurement_source        VARCHAR(50)  NULL,
    is_self_reported          BIT(1)       NOT NULL DEFAULT b'1',
    nurse_verified            BIT(1)       NOT NULL DEFAULT b'0',
    verified_at               DATETIME(6)  NULL,
    verified_by               VARCHAR(255) NULL,
    notes                     TEXT         NULL,
    PRIMARY KEY (id),
    KEY idx_vitals_patient (patient_id),
    CONSTRAINT fk_vitals_patient     FOREIGN KEY (patient_id)     REFERENCES patients (id),
    CONSTRAINT fk_vitals_appointment FOREIGN KEY (appointment_id) REFERENCES appointments (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE centre_vitals (
    id                        BIGINT       NOT NULL AUTO_INCREMENT,
    created_at                DATETIME(6)  NOT NULL,
    updated_at                DATETIME(6)  NULL,
    centre_patient_id         BIGINT       NOT NULL,
    centre_appointment_id     BIGINT       NULL,
    blood_pressure_systolic   INT          NULL,
    blood_pressure_diastolic  INT          NULL,
    heart_rate                INT          NULL,
    respiratory_rate          INT          NULL,
    temperature               DOUBLE       NULL,
    weight_kg                 DOUBLE       NULL,
    height_cm                 DOUBLE       NULL,
    bmi                       DOUBLE       NULL,
    blood_oxygen              INT          NULL,
    blood_glucose             DOUBLE       NULL,
    measured_at               DATETIME(6)  NOT NULL,
    measurement_source        VARCHAR(50)  NULL,
    is_self_reported          BIT(1)       NOT NULL DEFAULT b'1',
    notes                     TEXT         NULL,
    PRIMARY KEY (id),
    KEY idx_centre_vitals_patient (centre_patient_id),
    CONSTRAINT fk_centre_vitals_patient FOREIGN KEY (centre_patient_id)     REFERENCES centre_patients (id),
    CONSTRAINT fk_centre_vitals_appt    FOREIGN KEY (centre_appointment_id) REFERENCES centre_appointments (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Consultations
-- ---------------------------------------------------------------------
CREATE TABLE consultations (
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NULL,
    appointment_id           BIGINT       NOT NULL,
    patient_id               BIGINT       NOT NULL,
    doctor_id                BIGINT       NOT NULL,
    started_at               DATETIME(6)  NULL,
    ended_at                 DATETIME(6)  NULL,
    modality                 VARCHAR(30)  NOT NULL DEFAULT 'VIDEO',
    outcome                  VARCHAR(30)  NULL,
    termination_reason       VARCHAR(50)  NULL,
    termination_note         TEXT         NULL,
    identity_confirmed       BIT(1)       NOT NULL DEFAULT b'0',
    has_audio_fallback       BIT(1)       NOT NULL DEFAULT b'0',
    recording_consent_given  BIT(1)       NOT NULL DEFAULT b'0',
    escalation_instruction   TEXT         NULL,
    room_provider_id         VARCHAR(100) NULL,
    scheduled_start_at       DATETIME(6)  NULL,
    scheduled_end_at         DATETIME(6)  NULL,
    patient_joined_at        DATETIME(6)  NULL,
    doctor_joined_at         DATETIME(6)  NULL,
    warning_one_sent_at      DATETIME(6)  NULL,
    warning_two_sent_at      DATETIME(6)  NULL,
    terminated_by_id         BIGINT       NULL,
    safety_action_taken      TEXT         NULL,
    PRIMARY KEY (id),
    KEY idx_consultations_appointment (appointment_id),
    KEY idx_consultations_doctor (doctor_id),
    CONSTRAINT fk_consultations_appointment    FOREIGN KEY (appointment_id)   REFERENCES appointments (id),
    CONSTRAINT fk_consultations_patient        FOREIGN KEY (patient_id)       REFERENCES patients (id),
    CONSTRAINT fk_consultations_doctor         FOREIGN KEY (doctor_id)        REFERENCES users (id),
    CONSTRAINT fk_consultations_terminated_by  FOREIGN KEY (terminated_by_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE centre_consultations (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    created_at              DATETIME(6)  NOT NULL,
    updated_at              DATETIME(6)  NULL,
    centre_appointment_id   BIGINT       NOT NULL,
    centre_patient_id       BIGINT       NOT NULL,
    doctor_id               BIGINT       NOT NULL,
    started_at              DATETIME(6)  NULL,
    ended_at                DATETIME(6)  NULL,
    modality                VARCHAR(30)  NOT NULL DEFAULT 'VIDEO',
    outcome                 VARCHAR(30)  NULL,
    termination_reason      VARCHAR(50)  NULL,
    termination_note        TEXT         NULL,
    identity_confirmed      BIT(1)       NOT NULL DEFAULT b'0',
    has_audio_fallback      BIT(1)       NOT NULL DEFAULT b'0',
    escalation_instruction  TEXT         NULL,
    room_provider_id        VARCHAR(100) NULL,
    scheduled_start_at      DATETIME(6)  NULL,
    scheduled_end_at        DATETIME(6)  NULL,
    warning_one_sent_at     DATETIME(6)  NULL,
    warning_two_sent_at     DATETIME(6)  NULL,
    terminated_by_id        BIGINT       NULL,
    safety_action_taken     TEXT         NULL,
    PRIMARY KEY (id),
    KEY idx_centre_consultations_appt (centre_appointment_id),
    CONSTRAINT fk_centre_consult_appt          FOREIGN KEY (centre_appointment_id) REFERENCES centre_appointments (id),
    CONSTRAINT fk_centre_consult_patient       FOREIGN KEY (centre_patient_id)     REFERENCES centre_patients (id),
    CONSTRAINT fk_centre_consult_doctor        FOREIGN KEY (doctor_id)             REFERENCES users (id),
    CONSTRAINT fk_centre_consult_terminated_by FOREIGN KEY (terminated_by_id)      REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Clinical notes. Optional on the FNPH pathway, authoritative on the Centre pathway.
CREATE TABLE consultation_notes (
    id                        BIGINT       NOT NULL AUTO_INCREMENT,
    created_at                DATETIME(6)  NOT NULL,
    updated_at                DATETIME(6)  NULL,
    consultation_id           BIGINT       NOT NULL,
    clinical_note             LONGTEXT     NULL,
    is_authoritative          BIT(1)       NOT NULL DEFAULT b'0',
    is_signed                 BIT(1)       NOT NULL DEFAULT b'0',
    signed_at                 DATETIME(6)  NULL,
    signed_by                 VARCHAR(255) NULL,
    follow_up_recommendation  TEXT         NULL,
    follow_up_timeline        VARCHAR(50)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_consultation_notes_consultation (consultation_id),
    CONSTRAINT fk_consultation_notes_consultation FOREIGN KEY (consultation_id) REFERENCES consultations (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE centre_consultation_notes (
    id                        BIGINT       NOT NULL AUTO_INCREMENT,
    created_at                DATETIME(6)  NOT NULL,
    updated_at                DATETIME(6)  NULL,
    centre_consultation_id    BIGINT       NOT NULL,
    clinical_note             LONGTEXT     NOT NULL,
    is_signed                 BIT(1)       NOT NULL DEFAULT b'0',
    signed_at                 DATETIME(6)  NULL,
    signed_by                 VARCHAR(255) NULL,
    follow_up_recommendation  TEXT         NULL,
    follow_up_timeline        VARCHAR(50)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_centre_notes_consultation (centre_consultation_id),
    CONSTRAINT fk_centre_notes_consultation FOREIGN KEY (centre_consultation_id) REFERENCES centre_consultations (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Clinical outputs. Dual-owned so the Centre release bundle can be
-- assembled without duplicating every clinical entity.
-- ---------------------------------------------------------------------
CREATE TABLE prescriptions (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    created_at              DATETIME(6)  NOT NULL,
    updated_at              DATETIME(6)  NULL,
    consultation_id         BIGINT       NULL,
    centre_consultation_id  BIGINT       NULL,
    patient_id              BIGINT       NULL,
    centre_patient_id       BIGINT       NULL,
    doctor_id               BIGINT       NOT NULL,
    issue_number            VARCHAR(50)  NOT NULL,
    status                  VARCHAR(30)  NOT NULL DEFAULT 'DRAFT',
    not_required            BIT(1)       NOT NULL DEFAULT b'0',
    not_required_reason     VARCHAR(500) NULL,
    issue_date              DATE         NULL,
    expiry_date             DATE         NULL,
    validity_days           INT          NOT NULL DEFAULT 7,
    clinical_information    TEXT         NULL,
    supersedes_id           BIGINT       NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_prescriptions_issue_number (issue_number),
    KEY idx_prescriptions_status (status),
    KEY idx_prescriptions_patient (patient_id),
    CONSTRAINT fk_prescriptions_consultation        FOREIGN KEY (consultation_id)        REFERENCES consultations (id),
    CONSTRAINT fk_prescriptions_centre_consultation FOREIGN KEY (centre_consultation_id) REFERENCES centre_consultations (id),
    CONSTRAINT fk_prescriptions_patient             FOREIGN KEY (patient_id)             REFERENCES patients (id),
    CONSTRAINT fk_prescriptions_centre_patient      FOREIGN KEY (centre_patient_id)      REFERENCES centre_patients (id),
    CONSTRAINT fk_prescriptions_doctor              FOREIGN KEY (doctor_id)              REFERENCES users (id),
    -- Exactly one pathway owns a prescription. Enforced in the database, not
    -- only in the service layer.
    CONSTRAINT ck_prescriptions_one_pathway CHECK (
        (consultation_id IS NOT NULL AND centre_consultation_id IS NULL)
     OR (consultation_id IS NULL AND centre_consultation_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE prescription_items (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NULL,
    prescription_id  BIGINT       NOT NULL,
    medication       VARCHAR(255) NOT NULL,
    strength         VARCHAR(100) NULL,
    frequency        VARCHAR(100) NOT NULL,
    duration         VARCHAR(100) NULL,
    instructions     TEXT         NULL,
    sequence         INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_prescription_items_parent (prescription_id),
    CONSTRAINT fk_prescription_items_parent FOREIGN KEY (prescription_id) REFERENCES prescriptions (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE investigations (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    created_at              DATETIME(6)  NOT NULL,
    updated_at              DATETIME(6)  NULL,
    consultation_id         BIGINT       NULL,
    centre_consultation_id  BIGINT       NULL,
    patient_id              BIGINT       NULL,
    centre_patient_id       BIGINT       NULL,
    doctor_id               BIGINT       NOT NULL,
    issue_number            VARCHAR(50)  NOT NULL,
    status                  VARCHAR(30)  NOT NULL DEFAULT 'DRAFT',
    not_required            BIT(1)       NOT NULL DEFAULT b'0',
    not_required_reason     VARCHAR(500) NULL,
    clinical_information    TEXT         NULL,
    issue_date              DATE         NULL,
    expiry_date             DATE         NULL,
    validity_days           INT          NOT NULL DEFAULT 7,
    supersedes_id           BIGINT       NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_investigations_issue_number (issue_number),
    KEY idx_investigations_status (status),
    KEY idx_investigations_patient (patient_id),
    CONSTRAINT fk_investigations_consultation        FOREIGN KEY (consultation_id)        REFERENCES consultations (id),
    CONSTRAINT fk_investigations_centre_consultation FOREIGN KEY (centre_consultation_id) REFERENCES centre_consultations (id),
    CONSTRAINT fk_investigations_patient             FOREIGN KEY (patient_id)             REFERENCES patients (id),
    CONSTRAINT fk_investigations_centre_patient      FOREIGN KEY (centre_patient_id)      REFERENCES centre_patients (id),
    CONSTRAINT fk_investigations_doctor              FOREIGN KEY (doctor_id)              REFERENCES users (id),
    CONSTRAINT ck_investigations_one_pathway CHECK (
        (consultation_id IS NOT NULL AND centre_consultation_id IS NULL)
     OR (consultation_id IS NULL AND centre_consultation_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE investigation_items (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NULL,
    investigation_id  BIGINT       NOT NULL,
    panel_name        VARCHAR(150) NOT NULL,
    panel_code        VARCHAR(50)  NULL,
    notes             TEXT         NULL,
    sequence          INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_investigation_items_parent (investigation_id),
    CONSTRAINT fk_investigation_items_parent FOREIGN KEY (investigation_id) REFERENCES investigations (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE follow_ups (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    created_at              DATETIME(6)  NOT NULL,
    updated_at              DATETIME(6)  NULL,
    consultation_id         BIGINT       NULL,
    centre_consultation_id  BIGINT       NULL,
    patient_id              BIGINT       NULL,
    centre_patient_id       BIGINT       NULL,
    recommendation          TEXT         NULL,
    review_interval         VARCHAR(50)  NULL,
    expected_timeframe      VARCHAR(50)  NULL,
    preferred_date          DATE         NULL,
    preferred_time          TIME(6)      NULL,
    consultation_mode       VARCHAR(30)  NULL,
    status                  VARCHAR(30)  NOT NULL DEFAULT 'RECOMMENDED',
    scheduled_date          DATE         NULL,
    completed_date          DATE         NULL,
    notes                   TEXT         NULL,
    PRIMARY KEY (id),
    KEY idx_follow_ups_status (status),
    CONSTRAINT fk_follow_ups_consultation        FOREIGN KEY (consultation_id)        REFERENCES consultations (id),
    CONSTRAINT fk_follow_ups_centre_consultation FOREIGN KEY (centre_consultation_id) REFERENCES centre_consultations (id),
    CONSTRAINT fk_follow_ups_patient             FOREIGN KEY (patient_id)             REFERENCES patients (id),
    CONSTRAINT fk_follow_ups_centre_patient      FOREIGN KEY (centre_patient_id)      REFERENCES centre_patients (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Payments and centre wallet
-- ---------------------------------------------------------------------
CREATE TABLE payments (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    created_at              DATETIME(6)   NOT NULL,
    updated_at              DATETIME(6)   NULL,
    patient_id              BIGINT        NULL,
    appointment_id          BIGINT        NULL,
    purpose                 VARCHAR(50)   NOT NULL,
    reference               VARCHAR(50)   NOT NULL,
    remita_reference        VARCHAR(100)  NULL,
    amount                  DECIMAL(19,2) NOT NULL,
    currency                VARCHAR(3)    NOT NULL DEFAULT 'NGN',
    status                  VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    initiated_at            DATETIME(6)   NULL,
    expires_at              DATETIME(6)   NULL,
    payment_date            DATETIME(6)   NULL,
    verified_at             DATETIME(6)   NULL,
    failure_reason          TEXT          NULL,
    reconciliation_status   VARCHAR(20)   NULL,
    reconciliation_notes    TEXT          NULL,
    is_manually_reconciled  BIT(1)        NOT NULL DEFAULT b'0',
    refunded_at             DATETIME(6)   NULL,
    refund_reference        VARCHAR(100)  NULL,
    version                 BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payments_reference (reference),
    KEY idx_payments_status (status),
    KEY idx_payments_patient (patient_id),
    KEY idx_payments_remita_ref (remita_reference),
    CONSTRAINT fk_payments_patient     FOREIGN KEY (patient_id)     REFERENCES patients (id),
    CONSTRAINT fk_payments_appointment FOREIGN KEY (appointment_id) REFERENCES appointments (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE wallets (
    id                             BIGINT        NOT NULL AUTO_INCREMENT,
    created_at                     DATETIME(6)   NOT NULL,
    updated_at                     DATETIME(6)   NULL,
    centre_id                      BIGINT        NOT NULL,
    balance                        DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    last_reconciled_at             DATETIME(6)   NULL,
    last_credited_at               DATETIME(6)   NULL,
    last_debited_at                DATETIME(6)   NULL,
    warning_threshold              DECIMAL(19,2) NULL,
    critical_threshold             DECIMAL(19,2) NULL,
    low_balance_warning_sent       BIT(1)        NOT NULL DEFAULT b'0',
    critical_balance_warning_sent  BIT(1)        NOT NULL DEFAULT b'0',
    version                        BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wallets_centre (centre_id),
    CONSTRAINT fk_wallets_centre FOREIGN KEY (centre_id) REFERENCES centres (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Append-only. No updated_at column, and UPDATE/DELETE are revoked in V2.
CREATE TABLE wallet_transactions (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    created_at              DATETIME(6)   NOT NULL,
    centre_id               BIGINT        NOT NULL,
    wallet_id               BIGINT        NOT NULL,
    centre_appointment_id   BIGINT        NULL,
    transaction_reference   VARCHAR(50)   NOT NULL,
    direction               VARCHAR(10)   NOT NULL,
    status                  VARCHAR(20)   NOT NULL DEFAULT 'POSTED',
    amount                  DECIMAL(19,2) NOT NULL,
    balance_before          DECIMAL(19,2) NOT NULL,
    balance_after           DECIMAL(19,2) NOT NULL,
    description             TEXT          NULL,
    source                  VARCHAR(50)   NULL,
    reverses_transaction_id BIGINT        NULL,
    posted_at               DATETIME(6)   NOT NULL,
    posted_by               VARCHAR(100)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wallet_tx_reference (transaction_reference),
    KEY idx_wallet_tx_centre (centre_id, created_at),
    CONSTRAINT fk_wallet_tx_centre      FOREIGN KEY (centre_id)             REFERENCES centres (id),
    CONSTRAINT fk_wallet_tx_wallet      FOREIGN KEY (wallet_id)             REFERENCES wallets (id),
    CONSTRAINT fk_wallet_tx_appointment FOREIGN KEY (centre_appointment_id) REFERENCES centre_appointments (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Documents, uploads, notifications
-- ---------------------------------------------------------------------
CREATE TABLE document_verifications (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NULL,
    document_type       VARCHAR(50)  NOT NULL,
    document_id         BIGINT       NOT NULL,
    issue_number        VARCHAR(50)  NOT NULL,
    verification_token  VARCHAR(64)  NOT NULL,
    verification_url    VARCHAR(500) NOT NULL,
    last_verified_at    DATETIME(6)  NULL,
    last_verified_ip    VARCHAR(45)  NULL,
    verification_count  INT          NOT NULL DEFAULT 0,
    is_valid            BIT(1)       NOT NULL DEFAULT b'1',
    revoked_at          DATETIME(6)  NULL,
    revoked_reason      VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_verifications_token (verification_token),
    KEY idx_doc_verifications_issue_number (issue_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE file_uploads (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NULL,
    patient_id          BIGINT       NULL,
    centre_patient_id   BIGINT       NULL,
    centre_id           BIGINT       NULL,
    original_file_name  VARCHAR(255) NOT NULL,
    storage_bucket      VARCHAR(100) NOT NULL,
    storage_key         VARCHAR(500) NOT NULL,
    file_size           BIGINT       NOT NULL,
    mime_type           VARCHAR(100) NOT NULL,
    checksum            VARCHAR(64)  NOT NULL,
    category            VARCHAR(50)  NOT NULL,
    scan_status         VARCHAR(20)  NOT NULL DEFAULT 'UPLOADED',
    uploaded_by         VARCHAR(100) NULL,
    uploaded_at         DATETIME(6)  NOT NULL,
    scanned_at          DATETIME(6)  NULL,
    scan_result         TEXT         NULL,
    quarantined_at      DATETIME(6)  NULL,
    quarantine_reason   VARCHAR(500) NULL,
    description         TEXT         NULL,
    reference_id        VARCHAR(50)  NULL,
    PRIMARY KEY (id),
    KEY idx_file_uploads_patient (patient_id),
    KEY idx_file_uploads_centre (centre_id),
    KEY idx_file_uploads_scan (scan_status),
    CONSTRAINT fk_file_uploads_patient        FOREIGN KEY (patient_id)        REFERENCES patients (id),
    CONSTRAINT fk_file_uploads_centre_patient FOREIGN KEY (centre_patient_id) REFERENCES centre_patients (id),
    CONSTRAINT fk_file_uploads_centre         FOREIGN KEY (centre_id)         REFERENCES centres (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE notifications (
    id                        BIGINT       NOT NULL AUTO_INCREMENT,
    created_at                DATETIME(6)  NOT NULL,
    updated_at                DATETIME(6)  NULL,
    user_id                   BIGINT       NOT NULL,
    type                      VARCHAR(50)  NOT NULL,
    channel                   VARCHAR(20)  NOT NULL,
    status                    VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    template_code             VARCHAR(100) NULL,
    subject                   VARCHAR(255) NOT NULL,
    body                      TEXT         NOT NULL,
    contains_clinical_detail  BIT(1)       NOT NULL DEFAULT b'0',
    scheduled_for             DATETIME(6)  NULL,
    sent_at                   DATETIME(6)  NULL,
    delivered_at              DATETIME(6)  NULL,
    read_at                   DATETIME(6)  NULL,
    failure_reason            TEXT         NULL,
    retry_count               INT          NOT NULL DEFAULT 0,
    max_retries               INT          NOT NULL DEFAULT 3,
    reference_id              VARCHAR(50)  NULL,
    priority                  INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_notifications_user_status (user_id, status),
    KEY idx_notifications_scheduled (scheduled_for, status),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE notification_preferences (
    id                     BIGINT      NOT NULL AUTO_INCREMENT,
    created_at             DATETIME(6) NOT NULL,
    updated_at             DATETIME(6) NULL,
    user_id                BIGINT      NOT NULL,
    email_enabled          BIT(1)      NOT NULL DEFAULT b'1',
    in_app_enabled         BIT(1)      NOT NULL DEFAULT b'1',
    sms_enabled            BIT(1)      NOT NULL DEFAULT b'0',
    push_enabled           BIT(1)      NOT NULL DEFAULT b'0',
    appointment_reminders  BIT(1)      NOT NULL DEFAULT b'1',
    payment_notifications  BIT(1)      NOT NULL DEFAULT b'1',
    clinical_updates       BIT(1)      NOT NULL DEFAULT b'1',
    system_alerts          BIT(1)      NOT NULL DEFAULT b'1',
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_prefs_user (user_id),
    CONSTRAINT fk_notification_prefs_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Audit. Append-only. effective_principal_id records the account a Central
-- Administrator was acting as during a supervised session.
-- ---------------------------------------------------------------------
CREATE TABLE audit_logs (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    created_at              DATETIME(6)  NOT NULL,
    user_id                 BIGINT       NULL,
    username                VARCHAR(50)  NULL,
    action                  VARCHAR(100) NOT NULL,
    entity_type             VARCHAR(50)  NULL,
    entity_id               BIGINT       NULL,
    details                 TEXT         NULL,
    ip_address              VARCHAR(45)  NULL,
    user_agent              TEXT         NULL,
    performed_at            DATETIME(6)  NOT NULL,
    is_system               BIT(1)       NOT NULL DEFAULT b'0',
    effective_principal_id  BIGINT       NULL,
    view_as_session_id      BIGINT       NULL,
    centre_id               BIGINT       NULL,
    outcome                 VARCHAR(20)  NOT NULL DEFAULT 'SUCCESS',
    before_hash             VARCHAR(64)  NULL,
    after_hash              VARCHAR(64)  NULL,
    PRIMARY KEY (id),
    KEY idx_audit_logs_user_time (user_id, performed_at),
    KEY idx_audit_logs_entity (entity_type, entity_id),
    KEY idx_audit_logs_centre (centre_id, performed_at),
    CONSTRAINT fk_audit_logs_user      FOREIGN KEY (user_id)                REFERENCES users (id),
    CONSTRAINT fk_audit_logs_effective FOREIGN KEY (effective_principal_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
