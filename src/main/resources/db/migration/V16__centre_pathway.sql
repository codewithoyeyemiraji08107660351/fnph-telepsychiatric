-- =====================================================================
-- V16 - Centre of Excellence pathway
--
-- A REFERRAL IS PER VISIT, NOT PER PATIENT
--   referral_reason, assessment, current_condition and relevant_medicines
--   were columns on centre_patients. A patient seen three times over a year
--   has three presenting conditions, and holding them on the patient row means
--   each new referral silently overwrites the last.
--
--   The clinical history is the point of a referral. Losing it is losing the
--   reason the consultation happened. They move to centre_referrals, one per
--   request, and the existing values are carried across as each patient's
--   first referral rather than discarded.
--
-- THE WALLET IS DEBITED AT APPROVAL
--   The specification leaves debit timing open: at approval, or at completed
--   consultation. Approval is chosen because that is when FNPH commits a
--   doctor, a room and a multidisciplinary team to a slot no other centre can
--   then use. A no-show after that costs the hospital exactly as much as an
--   attended session.
--
--   A booking rejected or returned never reaches approval, so it never debits.
--   This is written down here because the timing decides what happens on a
--   cancellation, and a later reader needs to know it was decided rather than
--   defaulted.
--
-- CENTRES NEVER SEE AMOUNTS
--   Finance credits the wallet from programme funding. The centre sees
--   consultation and utilisation counts. That boundary is in the permission
--   matrix, and the receipts table below carries counts rather than naira for
--   the same reason.
-- =====================================================================

CREATE TABLE centre_referrals (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    public_id            VARCHAR(26)  NULL,
    created_at           DATETIME(6)  NOT NULL,
    created_by           VARCHAR(100) NULL,
    updated_at           DATETIME(6)  NULL,
    updated_by           VARCHAR(100) NULL,

    centre_id            BIGINT       NOT NULL,
    centre_patient_id    BIGINT       NOT NULL,
    reference            VARCHAR(50)  NOT NULL,

    referral_reason      TEXT         NOT NULL,
    assessment           TEXT         NULL,
    current_condition    TEXT         NULL,
    relevant_medicines   TEXT         NULL,
    previous_results     TEXT         NULL,

    -- Consent is per referral, not per patient. A patient consented to a
    -- consultation in March; that is not consent to one in September.
    consent_version      VARCHAR(20)  NULL,
    consent_accepted_at  DATETIME(6)  NULL,
    consent_accepted_by  VARCHAR(150) NULL,

    urgency              VARCHAR(20)  NOT NULL DEFAULT 'ROUTINE',
    status               VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    submitted_at         DATETIME(6)  NULL,
    submitted_by         VARCHAR(100) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_centre_referrals_public_id (public_id),
    UNIQUE KEY uk_centre_referrals_reference (reference),
    KEY idx_centre_referrals_centre (centre_id, created_at),
    KEY idx_centre_referrals_patient (centre_patient_id, created_at),
    CONSTRAINT fk_centre_referrals_centre  FOREIGN KEY (centre_id) REFERENCES centres (id),
    CONSTRAINT fk_centre_referrals_patient FOREIGN KEY (centre_patient_id) REFERENCES centre_patients (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Carry the existing values across as each patient's first referral, so no
-- clinical context is lost in the move.
INSERT INTO centre_referrals
    (created_at, created_by, centre_id, centre_patient_id, reference,
     referral_reason, assessment, current_condition, relevant_medicines,
     consent_version, consent_accepted_at, status, submitted_at, submitted_by)
SELECT p.created_at, 'system', p.centre_id, p.id,
       CONCAT('REF-MIG-', LPAD(p.id, 8, '0')),
       COALESCE(p.referral_reason, 'Migrated from the patient record in V16'),
       p.assessment, p.current_condition, p.relevant_medicines,
       p.consent_version, p.consent_accepted_at,
       'SUBMITTED', p.created_at, 'system'
FROM centre_patients p;

ALTER TABLE centre_patients
    DROP COLUMN referral_reason,
    DROP COLUMN assessment,
    DROP COLUMN current_condition,
    DROP COLUMN relevant_medicines,
    DROP COLUMN consent_version,
    DROP COLUMN consent_accepted_at;

-- ---------------------------------------------------------------------
-- Centre appointment: bind to the referral, the slot and the wallet entry
-- ---------------------------------------------------------------------
ALTER TABLE centre_appointments
    ADD COLUMN referral_id           BIGINT      NULL AFTER centre_patient_id,
    ADD COLUMN slot_id               BIGINT      NULL AFTER referral_id,
    ADD COLUMN room_id               BIGINT      NULL AFTER room,
    ADD COLUMN him_id                BIGINT      NULL AFTER laboratory_id,
    -- The ledger entry that paid for this booking. Present only after
    -- approval, which is what makes "was this booking charged" answerable.
    ADD COLUMN wallet_transaction_id BIGINT      NULL AFTER approved_at,
    ADD COLUMN wallet_debited_at     DATETIME(6) NULL AFTER wallet_transaction_id;

CREATE UNIQUE INDEX uk_centre_appointments_slot ON centre_appointments (slot_id);
CREATE INDEX idx_centre_appointments_referral ON centre_appointments (referral_id);

ALTER TABLE centre_appointments
    ADD CONSTRAINT fk_centre_appointments_referral FOREIGN KEY (referral_id) REFERENCES centre_referrals (id),
    ADD CONSTRAINT fk_centre_appointments_slot     FOREIGN KEY (slot_id) REFERENCES slots (id),
    ADD CONSTRAINT fk_centre_appointments_room_id  FOREIGN KEY (room_id) REFERENCES rooms (id),
    ADD CONSTRAINT fk_centre_appointments_him      FOREIGN KEY (him_id) REFERENCES users (id),
    ADD CONSTRAINT fk_centre_appointments_wallet_tx FOREIGN KEY (wallet_transaction_id)
        REFERENCES wallet_transactions (id);

-- ---------------------------------------------------------------------
-- The centre's incoming queue
--
-- A released bundle arrives here. Centre staff open it and mark it treated,
-- and it moves to history. Modelled separately from the bundle because the
-- bundle is FNPH's record of what was produced, while this is the centre's
-- record of what they did with it, and the two answer different questions.
-- ---------------------------------------------------------------------
CREATE TABLE centre_bundle_receipts (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    public_id          VARCHAR(26)  NULL,
    created_at         DATETIME(6)  NOT NULL,
    created_by         VARCHAR(100) NULL,
    updated_at         DATETIME(6)  NULL,
    updated_by         VARCHAR(100) NULL,

    centre_id          BIGINT       NOT NULL,
    bundle_id          BIGINT       NOT NULL,
    centre_appointment_id BIGINT    NOT NULL,
    centre_patient_id  BIGINT       NOT NULL,

    delivered_at       DATETIME(6)  NOT NULL,
    first_opened_at    DATETIME(6)  NULL,
    first_opened_by    VARCHAR(100) NULL,
    treated_at         DATETIME(6)  NULL,
    treated_by         VARCHAR(100) NULL,
    treatment_notes    TEXT         NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_centre_bundle_receipts_public_id (public_id),
    UNIQUE KEY uk_centre_bundle_receipts_bundle (bundle_id),
    KEY idx_centre_bundle_receipts_queue (centre_id, treated_at, delivered_at),
    CONSTRAINT fk_centre_bundle_receipts_centre  FOREIGN KEY (centre_id) REFERENCES centres (id),
    CONSTRAINT fk_centre_bundle_receipts_bundle  FOREIGN KEY (bundle_id) REFERENCES release_bundles (id),
    CONSTRAINT fk_centre_bundle_receipts_appt    FOREIGN KEY (centre_appointment_id) REFERENCES centre_appointments (id),
    CONSTRAINT fk_centre_bundle_receipts_patient FOREIGN KEY (centre_patient_id) REFERENCES centre_patients (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Wallet alerts
--
-- Finance is told at 20 percent and again at 10 percent. Recorded so an alert
-- fires once per threshold crossing rather than on every booking below it,
-- which would train Finance to ignore them.
-- ---------------------------------------------------------------------
CREATE TABLE wallet_alerts (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    public_id      VARCHAR(26)   NULL,
    created_at     DATETIME(6)   NOT NULL,
    created_by     VARCHAR(100)  NULL,
    updated_at     DATETIME(6)   NULL,
    updated_by     VARCHAR(100)  NULL,

    centre_id      BIGINT        NOT NULL,
    wallet_id      BIGINT        NOT NULL,
    alert_level    VARCHAR(20)   NOT NULL,
    balance_at_alert DECIMAL(19,2) NOT NULL,
    threshold_amount DECIMAL(19,2) NOT NULL,
    raised_at      DATETIME(6)   NOT NULL,
    cleared_at     DATETIME(6)   NULL,
    acknowledged_at DATETIME(6)  NULL,
    acknowledged_by VARCHAR(100) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_wallet_alerts_public_id (public_id),
    KEY idx_wallet_alerts_open (centre_id, alert_level, cleared_at),
    CONSTRAINT fk_wallet_alerts_centre FOREIGN KEY (centre_id) REFERENCES centres (id),
    CONSTRAINT fk_wallet_alerts_wallet FOREIGN KEY (wallet_id) REFERENCES wallets (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
