-- =====================================================================
-- V11 - Remita payment, patient credit, in-app notification
--
-- EXACTLY-ONCE, VIA AN INBOX
--   Remita retries. A dropped response, a slow reply or a network blip all
--   produce a second callback for the same transaction. Without a unique
--   constraint on the raw event, the second one credits the patient twice,
--   unlocks slot selection twice, or posts two ledger entries.
--
--   webhook_inbox stores every callback keyed by a hash of its payload. The
--   unique index is what makes the second one a no-op rather than a bug.
--   "Duplicate Remita callbacks produce one payment state" is an acceptance
--   gate, and this table is the thing that passes it.
--
-- THE BROWSER RETURN PAGE IS NOT EVIDENCE
--   A success page is a URL the patient's browser was sent to. It can be
--   opened directly, replayed, or reached after a failed payment. Only a
--   server-to-server verification against Remita, checking reference, amount,
--   currency and status, moves a payment to SUCCESS.
--
-- AN OUTBOX, BECAUSE TWO SYSTEMS CANNOT COMMIT TOGETHER
--   Marking a payment verified and unlocking slot selection must both happen
--   or neither. Writing the intent to outbox_events in the same transaction
--   as the payment, then publishing from there, is what makes that atomic
--   without a distributed transaction.
--
-- CREDIT, NOT REFUND
--   Payment is non-refundable by decision. But a patient pays before the Hub
--   Coordinator approves, so a rejection would otherwise leave them out of
--   pocket for an administrative decision they had no part in. The money
--   stays with FNPH and becomes a credit against their next booking. Same
--   cash position, no patient penalised for a rejection.
--
--   patient_credits is an append-only ledger for the same reason the centre
--   wallet is: balance is derived, never a mutable field treated as truth.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Every callback, stored before it is interpreted
-- ---------------------------------------------------------------------
CREATE TABLE webhook_inbox (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    public_id         VARCHAR(26)  NULL,
    created_at        DATETIME(6)  NOT NULL,
    created_by        VARCHAR(100) NULL,
    updated_at        DATETIME(6)  NULL,
    updated_by        VARCHAR(100) NULL,

    provider          VARCHAR(20)  NOT NULL,
    provider_event_id VARCHAR(150) NULL,

    -- The whole callback, as received. Stored before parsing so a payload the
    -- code cannot understand is still available to look at afterwards.
    payload           LONGTEXT     NOT NULL,

    -- The exactly-once mechanism. A repeat delivery collides here.
    payload_hash      VARCHAR(64)  NOT NULL,

    signature_valid   BIT(1)       NOT NULL DEFAULT b'0',
    received_at       DATETIME(6)  NOT NULL,
    processed_at      DATETIME(6)  NULL,
    processing_state  VARCHAR(20)  NOT NULL DEFAULT 'RECEIVED',
    failure_reason    VARCHAR(500) NULL,
    attempts          INT          NOT NULL DEFAULT 0,
    source_ip         VARCHAR(45)  NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_webhook_inbox_public_id (public_id),
    UNIQUE KEY uk_webhook_inbox_payload (payload_hash),
    KEY idx_webhook_inbox_state (processing_state, received_at),
    KEY idx_webhook_inbox_provider (provider, provider_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Intent written in the same transaction as the state change
-- ---------------------------------------------------------------------
CREATE TABLE outbox_events (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    public_id      VARCHAR(26)  NULL,
    created_at     DATETIME(6)  NOT NULL,
    created_by     VARCHAR(100) NULL,
    updated_at     DATETIME(6)  NULL,
    updated_by     VARCHAR(100) NULL,

    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   BIGINT       NOT NULL,
    event_type     VARCHAR(60)  NOT NULL,
    payload        LONGTEXT     NULL,

    published_at   DATETIME(6)  NULL,
    attempts       INT          NOT NULL DEFAULT 0,
    last_error     VARCHAR(500) NULL,
    next_attempt_at DATETIME(6) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_public_id (public_id),
    KEY idx_outbox_unpublished (published_at, next_attempt_at),
    KEY idx_outbox_aggregate (aggregate_type, aggregate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Payment: fields the Remita flow needs
-- ---------------------------------------------------------------------
ALTER TABLE payments
    -- Remita Retrieval Reference. Issued by Remita at initiation and quoted by
    -- the patient at any payment channel, so it is the number a support call
    -- will be about.
    ADD COLUMN rrr                    VARCHAR(50)  NULL AFTER remita_reference,
    ADD COLUMN payment_channel        VARCHAR(50)  NULL AFTER rrr,
    ADD COLUMN initiation_response    TEXT         NULL AFTER failure_reason,
    ADD COLUMN last_verified_at       DATETIME(6)  NULL AFTER verified_at,
    ADD COLUMN verification_attempts  INT          NOT NULL DEFAULT 0 AFTER last_verified_at,
    -- Set when the amount Remita reports differs from the order. Never
    -- auto-accepted: a mismatch is a Finance exception, not a rounding issue.
    ADD COLUMN amount_mismatch        BIT(1)       NOT NULL DEFAULT b'0' AFTER verification_attempts,
    ADD COLUMN reported_amount        DECIMAL(19,2) NULL AFTER amount_mismatch,
    -- Credit applied from an earlier non-refunded payment.
    ADD COLUMN credit_applied         DECIMAL(19,2) NOT NULL DEFAULT 0.00 AFTER amount;

CREATE UNIQUE INDEX uk_payments_rrr ON payments (rrr);

-- ---------------------------------------------------------------------
-- Patient credit. Append-only, same as the centre wallet ledger.
-- ---------------------------------------------------------------------
CREATE TABLE patient_credits (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    public_id          VARCHAR(26)   NULL,
    created_at         DATETIME(6)   NOT NULL,
    created_by         VARCHAR(100)  NULL,

    patient_id         BIGINT        NOT NULL,
    direction          VARCHAR(10)   NOT NULL,
    amount             DECIMAL(19,2) NOT NULL,
    balance_after      DECIMAL(19,2) NOT NULL,

    -- Why the credit exists or was consumed. A patient asking "where did my
    -- ten thousand naira go" gets an answer from this column.
    reason             VARCHAR(500)  NOT NULL,
    source_payment_id  BIGINT        NULL,
    applied_payment_id BIGINT        NULL,
    appointment_id     BIGINT        NULL,

    -- A credit that never expires is a liability with no end. FNPH sets the
    -- window; null means it does not expire.
    expires_at         DATETIME(6)   NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_patient_credits_public_id (public_id),
    KEY idx_patient_credits_patient (patient_id, created_at),
    CONSTRAINT fk_patient_credits_patient FOREIGN KEY (patient_id) REFERENCES patients (id),
    CONSTRAINT fk_patient_credits_source  FOREIGN KEY (source_payment_id) REFERENCES payments (id),
    CONSTRAINT fk_patient_credits_applied FOREIGN KEY (applied_payment_id) REFERENCES payments (id),
    CONSTRAINT ck_patient_credits_amount CHECK (amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Reconciliation
-- ---------------------------------------------------------------------
CREATE TABLE reconciliation_runs (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    public_id            VARCHAR(26)  NULL,
    created_at           DATETIME(6)  NOT NULL,
    created_by           VARCHAR(100) NULL,
    updated_at           DATETIME(6)  NULL,
    updated_by           VARCHAR(100) NULL,

    run_type             VARCHAR(20)  NOT NULL,
    period_start         DATETIME(6)  NOT NULL,
    period_end           DATETIME(6)  NOT NULL,
    started_at           DATETIME(6)  NOT NULL,
    completed_at         DATETIME(6)  NULL,
    transactions_checked INT          NOT NULL DEFAULT 0,
    matched_count        INT          NOT NULL DEFAULT 0,
    exception_count      INT          NOT NULL DEFAULT 0,
    run_by               VARCHAR(100) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_reconciliation_runs_public_id (public_id),
    KEY idx_reconciliation_runs_period (period_start, period_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE reconciliation_exceptions (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    public_id         VARCHAR(26)  NULL,
    created_at        DATETIME(6)  NOT NULL,
    created_by        VARCHAR(100) NULL,
    updated_at        DATETIME(6)  NULL,
    updated_by        VARCHAR(100) NULL,

    run_id            BIGINT       NOT NULL,
    payment_id        BIGINT       NULL,
    exception_type    VARCHAR(30)  NOT NULL,
    details           TEXT         NULL,
    expected_amount   DECIMAL(19,2) NULL,
    reported_amount   DECIMAL(19,2) NULL,
    resolved_at       DATETIME(6)  NULL,
    resolved_by       VARCHAR(100) NULL,
    resolution_notes  TEXT         NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_reconciliation_exceptions_public_id (public_id),
    KEY idx_reconciliation_exceptions_run (run_id, resolved_at),
    CONSTRAINT fk_reconciliation_exceptions_run     FOREIGN KEY (run_id) REFERENCES reconciliation_runs (id),
    CONSTRAINT fk_reconciliation_exceptions_payment FOREIGN KEY (payment_id) REFERENCES payments (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- In-app notification, targeted at a person or at a dashboard
--
-- A booking arriving for approval is not addressed to one Hub Coordinator; it
-- is addressed to whoever is on the desk. Targeting a role rather than a
-- person is what makes the dashboard count correct when someone is on leave.
-- ---------------------------------------------------------------------
ALTER TABLE notifications
    ADD COLUMN target_role_id  BIGINT       NULL AFTER user_id,
    ADD COLUMN centre_id       BIGINT       NULL AFTER target_role_id,
    ADD COLUMN patient_id      BIGINT       NULL AFTER centre_id,
    -- Where clicking it should take the user.
    ADD COLUMN action_url      VARCHAR(300) NULL AFTER body,
    ADD COLUMN entity_type     VARCHAR(50)  NULL AFTER action_url,
    ADD COLUMN entity_id       BIGINT       NULL AFTER entity_type,
    ADD COLUMN dismissed_at    DATETIME(6)  NULL AFTER read_at,
    ADD COLUMN acknowledged_by_id BIGINT    NULL AFTER dismissed_at;

-- user_id was NOT NULL. A role-targeted notification has no single recipient.
ALTER TABLE notifications MODIFY COLUMN user_id BIGINT NULL;

ALTER TABLE notifications
    ADD CONSTRAINT fk_notifications_role    FOREIGN KEY (target_role_id) REFERENCES roles (id),
    ADD CONSTRAINT fk_notifications_centre  FOREIGN KEY (centre_id) REFERENCES centres (id),
    ADD CONSTRAINT fk_notifications_patient FOREIGN KEY (patient_id) REFERENCES patients (id),
    ADD CONSTRAINT fk_notifications_ack     FOREIGN KEY (acknowledged_by_id) REFERENCES users (id),
    -- Exactly one addressee. Neither would be a notification nobody sees;
    -- both would be one delivered twice.
    ADD CONSTRAINT ck_notifications_addressee CHECK (
        (user_id IS NOT NULL AND target_role_id IS NULL)
     OR (user_id IS NULL AND target_role_id IS NOT NULL)
    );

CREATE INDEX idx_notifications_role_inbox ON notifications (target_role_id, centre_id, read_at);
CREATE INDEX idx_notifications_entity ON notifications (entity_type, entity_id);
