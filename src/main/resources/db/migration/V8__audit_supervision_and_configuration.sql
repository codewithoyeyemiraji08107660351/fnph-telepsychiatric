-- =====================================================================
-- V8 - supervised access, versioned configuration, tamper-evident audit
--
-- THREE THINGS HERE
--
-- 1. view_as_sessions
--    The Central Administrator may open any authorised dashboard as a
--    supervised session. That is an authorisation mode, not a role, and it
--    needs its own record: who, as whom, why, from where, for how long, and
--    everything done inside it.
--
-- 2. system_configuration and configuration_changes
--    Fees, timing values, thresholds and validity periods are governance
--    decisions that change without a release. Every change carries a reason,
--    the previous value and the new one, so a later question about why a
--    session ran 40 minutes on a given day has an answer.
--
-- 3. A hash chain on audit_logs
--    Database grants already revoke UPDATE and DELETE from the application
--    account. That stops the application. It does not stop someone with
--    database credentials, and "the audit trail cannot be altered" is a claim
--    that has to survive that person existing.
--
--    Each row's chain_hash covers its own content plus the previous row's
--    chain_hash. Editing or removing any row breaks every hash after it, and
--    the break is detectable by anyone who can read the table. Tamper-evident
--    rather than tamper-proof, which is the honest and achievable guarantee.
--
-- ON permissions.is_mutating
--    Supervised access is read-only. An administrator viewing a doctor's
--    dashboard must be able to see what the doctor sees and must not be able
--    to write a clinical note in their name, because the two would be
--    indistinguishable afterwards and the specification is explicit that
--    clinician-authored content is not altered by anyone else.
--
--    Marking each permission mutating or not is what makes that enforceable
--    rather than aspirational.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Supervised access
-- ---------------------------------------------------------------------
CREATE TABLE view_as_sessions (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    public_id           VARCHAR(26)  NULL,
    created_at          DATETIME(6)  NOT NULL,
    created_by          VARCHAR(100) NULL,
    updated_at          DATETIME(6)  NULL,
    updated_by          VARCHAR(100) NULL,

    administrator_id    BIGINT       NOT NULL,
    target_user_id      BIGINT       NOT NULL,
    target_role_id      BIGINT       NOT NULL,

    -- Mandatory, minimum length enforced in the API. "update" tells a future
    -- auditor nothing about why someone opened a clinician's dashboard.
    reason              VARCHAR(500) NOT NULL,

    started_at          DATETIME(6)  NOT NULL,
    ended_at            DATETIME(6)  NULL,
    -- A session left open is a supervised session nobody closed. Expiry means
    -- an administrator who walks away does not leave the mode running.
    expires_at          DATETIME(6)  NOT NULL,
    end_reason          VARCHAR(100) NULL,

    ip_address          VARCHAR(45)  NULL,
    user_agent          VARCHAR(500) NULL,
    actions_performed   INT          NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uk_view_as_public_id (public_id),
    KEY idx_view_as_administrator (administrator_id, started_at),
    KEY idx_view_as_target (target_user_id, started_at),
    KEY idx_view_as_open (ended_at, expires_at),
    CONSTRAINT fk_view_as_administrator FOREIGN KEY (administrator_id) REFERENCES users (id),
    CONSTRAINT fk_view_as_target        FOREIGN KEY (target_user_id)   REFERENCES users (id),
    CONSTRAINT fk_view_as_role          FOREIGN KEY (target_role_id)   REFERENCES roles (id),

    -- An administrator cannot supervise themselves. It would produce an audit
    -- trail that says nothing and a mode with no purpose.
    CONSTRAINT ck_view_as_not_self CHECK (administrator_id <> target_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Configuration
-- ---------------------------------------------------------------------
CREATE TABLE system_configuration (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    public_id      VARCHAR(26)   NULL,
    created_at     DATETIME(6)   NOT NULL,
    created_by     VARCHAR(100)  NULL,
    updated_at     DATETIME(6)   NULL,
    updated_by     VARCHAR(100)  NULL,

    config_key     VARCHAR(100)  NOT NULL,
    config_value   VARCHAR(1000) NOT NULL,
    value_type     VARCHAR(20)   NOT NULL,
    category       VARCHAR(50)   NOT NULL,
    description    VARCHAR(500)  NOT NULL,

    -- Bounds checked on write. A fee of zero or a session length of four
    -- hours is a typo, and the moment to catch it is before it reaches a
    -- patient-facing screen.
    min_value      VARCHAR(50)   NULL,
    max_value      VARCHAR(50)   NULL,
    allowed_values VARCHAR(500)  NULL,

    -- A governance-owned value cannot be changed without FNPH approval being
    -- recorded in the reason. Marked so the administration screen can say so.
    requires_governance BIT(1)   NOT NULL DEFAULT b'0',

    -- Hidden from the read API. Nothing sensitive is seeded here today; the
    -- flag exists so adding one later does not require remembering.
    is_sensitive   BIT(1)        NOT NULL DEFAULT b'0',

    effective_from DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_system_configuration_key (config_key),
    UNIQUE KEY uk_system_configuration_public_id (public_id),
    KEY idx_system_configuration_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Append-only history. Not a soft-deleted table: the reason a fee changed in
-- March must still be readable in December.
CREATE TABLE configuration_changes (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    public_id       VARCHAR(26)   NULL,
    created_at      DATETIME(6)   NOT NULL,
    created_by      VARCHAR(100)  NULL,

    configuration_id BIGINT       NOT NULL,
    config_key      VARCHAR(100)  NOT NULL,
    previous_value  VARCHAR(1000) NULL,
    new_value       VARCHAR(1000) NOT NULL,
    reason          VARCHAR(500)  NOT NULL,
    changed_by      VARCHAR(100)  NOT NULL,
    changed_at      DATETIME(6)   NOT NULL,
    effective_from  DATETIME(6)   NOT NULL,
    ip_address      VARCHAR(45)   NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_configuration_changes_public_id (public_id),
    KEY idx_configuration_changes_key (config_key, changed_at),
    CONSTRAINT fk_configuration_changes_config
        FOREIGN KEY (configuration_id) REFERENCES system_configuration (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Read-only supervision
-- ---------------------------------------------------------------------
ALTER TABLE permissions
    ADD COLUMN is_mutating BIT(1) NOT NULL DEFAULT b'1' AFTER description;

-- A permission is non-mutating exactly when its action is a read.
-- Everything else changes state and is therefore unavailable during
-- supervised access. document.download counts as mutating on purpose: it
-- consumes the patient's single allowed download, and an administrator
-- looking at a dashboard must not burn it.
UPDATE permissions SET is_mutating = b'0' WHERE code LIKE '%read%';

CREATE INDEX idx_permissions_mutating ON permissions (is_mutating);

-- ---------------------------------------------------------------------
-- Tamper-evident audit chain
-- ---------------------------------------------------------------------
ALTER TABLE audit_logs
    ADD COLUMN chain_hash          VARCHAR(64) NULL AFTER after_hash,
    ADD COLUMN previous_chain_hash VARCHAR(64) NULL AFTER chain_hash,
    ADD COLUMN reason              VARCHAR(500) NULL AFTER details;

CREATE INDEX idx_audit_logs_chain ON audit_logs (chain_hash);
CREATE INDEX idx_audit_logs_action ON audit_logs (action, performed_at);

-- ---------------------------------------------------------------------
-- Seeded configuration
--
-- The first three keys are separate deliberately. The source documents give
-- three different numbers for what reads like one setting: a five-minute
-- grace period in the consent text, a fifteen-minute no-show cutoff in the
-- specification, and a fifteen-minute room-open lead in the prototype. They
-- are three parameters, not one, and merging them in configuration would
-- reproduce the ambiguity in code.
--
-- Session length is per audience rather than global, so changing the Centre
-- duration cannot silently move FNPH appointments.
-- ---------------------------------------------------------------------
INSERT INTO system_configuration
    (created_at, created_by, config_key, config_value, value_type, category,
     description, min_value, max_value, allowed_values, requires_governance,
     is_sensitive, effective_from)
VALUES
  (UTC_TIMESTAMP(6),'system','room_open_lead_minutes','15','INTEGER','consultation','How early before the slot start the join control activates','5','60',NULL,b'1',b'0',UTC_TIMESTAMP(6)),
  (UTC_TIMESTAMP(6),'system','joining_grace_minutes','5','INTEGER','consultation','How late a patient may join before being flagged late','0','15',NULL,b'1',b'0',UTC_TIMESTAMP(6)),
  (UTC_TIMESTAMP(6),'system','no_show_cutoff_minutes','15','INTEGER','consultation','How long after the start time the link deactivates and a no-show is recorded','5','30',NULL,b'1',b'0',UTC_TIMESTAMP(6)),
  (UTC_TIMESTAMP(6),'system','session_minutes_fnph','30','INTEGER','consultation','Fixed consultation length on the FNPH patient pathway','20','60',NULL,b'1',b'0',UTC_TIMESTAMP(6)),
  (UTC_TIMESTAMP(6),'system','session_minutes_centre','30','INTEGER','consultation','Default consultation length on the Centre pathway','20','60',NULL,b'1',b'0',UTC_TIMESTAMP(6)),
  (UTC_TIMESTAMP(6),'system','warning_one_minutes_remaining','15','INTEGER','consultation','First countdown warning, in minutes remaining','5','30',NULL,b'1',b'0',UTC_TIMESTAMP(6)),
  (UTC_TIMESTAMP(6),'system','warning_two_minutes_remaining','10','INTEGER','consultation','Second countdown warning, shown in red','1','20',NULL,b'1',b'0',UTC_TIMESTAMP(6)),
  (UTC_TIMESTAMP(6),'system','recording_enabled','false','BOOLEAN','consultation','Session recording. Stays off until FNPH approves consent, retention, access, data location, deletion and incident response',NULL,NULL,'true,false',b'1',b'0',UTC_TIMESTAMP(6)),
  (UTC_TIMESTAMP(6),'system','slot_hold_ttl_minutes','10','INTEGER','scheduling','How long a requested slot stays reserved before it returns to the pool','2','30',NULL,b'1',b'0',UTC_TIMESTAMP(6)),
  (UTC_TIMESTAMP(6),'system','cancellation_notice_hours','24','INTEGER','scheduling','Minimum notice for a patient or centre to cancel or reschedule','0','72',NULL,b'1',b'0',UTC_TIMESTAMP(6)),
  (UTC_TIMESTAMP(6),'system','consultation_fee_ngn','10000','INTEGER','finance','FNPH patient consultation fee in naira','0','1000000',NULL,b'1',b'0',UTC_TIMESTAMP(6)),
  (UTC_TIMESTAMP(6),'system','centre_booking_charge_ngn','5000','INTEGER','finance','Wallet debit per approved centre booking, in naira','0','1000000',NULL,b'1',b'0',UTC_TIMESTAMP(6)),
  (UTC_TIMESTAMP(6),'system','wallet_warning_percent','20','INTEGER','finance','Centre wallet balance percentage that triggers a low-balance alert','1','100',NULL,b'1',b'0',UTC_TIMESTAMP(6)),
  (UTC_TIMESTAMP(6),'system','wallet_critical_percent','10','INTEGER','finance','Centre wallet balance percentage that triggers a critical alert','1','100',NULL,b'1',b'0',UTC_TIMESTAMP(6)),
  (UTC_TIMESTAMP(6),'system','prescription_validity_days','7','INTEGER','documents','How long a released prescription remains valid','1','90',NULL,b'1',b'0',UTC_TIMESTAMP(6)),
  (UTC_TIMESTAMP(6),'system','investigation_validity_days','7','INTEGER','documents','How long a released investigation request remains valid','1','90',NULL,b'1',b'0',UTC_TIMESTAMP(6)),
  (UTC_TIMESTAMP(6),'system','prescription_max_downloads','1','INTEGER','documents','Successful downloads allowed before a prescription becomes view-only','1','10',NULL,b'1',b'0',UTC_TIMESTAMP(6)),
  (UTC_TIMESTAMP(6),'system','investigation_max_downloads','1','INTEGER','documents','Successful downloads allowed for an investigation request. Still an open decision with FNPH; the value here is provisional','1','10',NULL,b'1',b'0',UTC_TIMESTAMP(6)),
  (UTC_TIMESTAMP(6),'system','clinical_emergency_number','08032722243','STRING','contact','Number given to anyone who reaches an emergency stop path or an excluded case',NULL,NULL,NULL,b'1',b'0',UTC_TIMESTAMP(6)),
  (UTC_TIMESTAMP(6),'system','helpdesk_email','support@fnphkaduna.gov.ng','STRING','contact','Address shown on support screens and in account emails',NULL,NULL,NULL,b'0',b'0',UTC_TIMESTAMP(6)),
  (UTC_TIMESTAMP(6),'system','helpdesk_first_response_minutes','10','INTEGER','support','Target for a first response on a support ticket','1','480',NULL,b'0',b'0',UTC_TIMESTAMP(6)),
  (UTC_TIMESTAMP(6),'system','session_inactivity_timeout_minutes','30','INTEGER','security','How long a signed-in session may sit idle before it ends','5','240',NULL,b'0',b'0',UTC_TIMESTAMP(6)),
  (UTC_TIMESTAMP(6),'system','max_upload_size_mb','20','INTEGER','uploads','Largest single upload accepted','1','100',NULL,b'0',b'0',UTC_TIMESTAMP(6)),
  (UTC_TIMESTAMP(6),'system','ehr_import_staleness_warning_days','7','INTEGER','ehr','Age at which the EHR verification snapshot is flagged as stale on screen','1','90',NULL,b'0',b'0',UTC_TIMESTAMP(6));

-- The initial value of every key, so history starts at creation rather than
-- at the first change. Without this the first edit would show a previous
-- value of nothing.
INSERT INTO configuration_changes
    (created_at, created_by, configuration_id, config_key, previous_value,
     new_value, reason, changed_by, changed_at, effective_from)
SELECT UTC_TIMESTAMP(6), 'system', c.id, c.config_key, NULL, c.config_value,
       'Initial value seeded in V8', 'system', UTC_TIMESTAMP(6), c.effective_from
FROM system_configuration c;
