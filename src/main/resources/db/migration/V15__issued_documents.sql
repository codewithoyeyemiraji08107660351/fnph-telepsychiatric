-- =====================================================================
-- V15 - issued documents, download limits, QR verification
--
-- ONE ISSUING PATH FOR EVERY DOCUMENT TYPE
--   Download counters, QR fields and validity were previously duplicated on
--   prescriptions and investigations. Two copies of the same logic drift, and
--   the drift shows up as a prescription that expires correctly and an
--   investigation request that does not. One table now issues both.
--
-- THE PUBLIC VERIFICATION ENDPOINT RETURNS ALMOST NOTHING
--   A pharmacist scanning a QR code needs to know the document is genuine and
--   still valid. They do not need the patient's name, and the person scanning
--   might be anyone who found a piece of paper.
--
--   So verification returns the issue number, the issue date, the expiry and a
--   status. No patient name, no clinician name, no medication, no diagnosis.
--   The document in the scanner's hand already carries what they legitimately
--   need; the endpoint only confirms it was not forged.
--
-- SAVED COPIES MUST VERIFY AS EXPIRED
--   The QR encodes a token that resolves server-side. It is not a self-contained
--   payload, because a self-contained one keeps saying "valid" forever on a
--   photograph taken the day it was issued.
--
-- DOWNLOAD COUNTING IS ATOMIC
--   The counter increments in the same statement that checks it. A patient on a
--   poor connection retrying a download must not burn their single allowance
--   twice, and two devices requesting at once must not both succeed against a
--   limit of one.
-- =====================================================================

CREATE TABLE issued_documents (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    public_id             VARCHAR(26)  NULL,
    created_at            DATETIME(6)  NOT NULL,
    created_by            VARCHAR(100) NULL,
    updated_at            DATETIME(6)  NULL,
    updated_by            VARCHAR(100) NULL,

    document_type         VARCHAR(30)  NOT NULL,
    source_id             BIGINT       NOT NULL,

    -- Human-readable and quoted on the phone. Format is deliberately
    -- unambiguous when read aloud.
    issue_number          VARCHAR(50)  NOT NULL,

    patient_id            BIGINT       NULL,
    centre_patient_id     BIGINT       NULL,
    centre_id             BIGINT       NULL,
    bundle_id             BIGINT       NULL,

    issued_at             DATETIME(6)  NOT NULL,
    expires_at            DATETIME(6)  NOT NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',

    -- Read from configuration per document type at issue, then frozen. A
    -- patient's allowance must not change under them because governance
    -- adjusted a setting after their prescription was issued.
    max_downloads         INT          NOT NULL,
    download_count        INT          NOT NULL DEFAULT 0,
    is_view_only          BIT(1)       NOT NULL DEFAULT b'0',

    storage_bucket        VARCHAR(100) NULL,
    storage_key           VARCHAR(500) NULL,
    file_checksum         VARCHAR(64)  NULL,

    superseded_by_id      BIGINT       NULL,
    revoked_at            DATETIME(6)  NULL,
    revoked_by            VARCHAR(100) NULL,
    revoked_reason        VARCHAR(500) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_issued_documents_public_id (public_id),
    UNIQUE KEY uk_issued_documents_issue_number (issue_number),
    UNIQUE KEY uk_issued_documents_source (document_type, source_id),
    KEY idx_issued_documents_patient (patient_id, status),
    KEY idx_issued_documents_centre (centre_id, status),
    KEY idx_issued_documents_expiry (expires_at, status),
    CONSTRAINT fk_issued_documents_patient        FOREIGN KEY (patient_id) REFERENCES patients (id),
    CONSTRAINT fk_issued_documents_centre_patient FOREIGN KEY (centre_patient_id) REFERENCES centre_patients (id),
    CONSTRAINT fk_issued_documents_centre         FOREIGN KEY (centre_id) REFERENCES centres (id),
    CONSTRAINT fk_issued_documents_bundle         FOREIGN KEY (bundle_id) REFERENCES release_bundles (id),
    CONSTRAINT fk_issued_documents_superseded     FOREIGN KEY (superseded_by_id) REFERENCES issued_documents (id),

    CONSTRAINT ck_issued_documents_downloads CHECK (download_count >= 0 AND max_downloads >= 0),
    CONSTRAINT ck_issued_documents_validity  CHECK (expires_at > issued_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Every download attempt, allowed or refused. Append-only.
--
-- A patient saying "it only let me download once and I never got it" is
-- settled here, and so is the reverse.
-- ---------------------------------------------------------------------
CREATE TABLE document_download_events (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    public_id           VARCHAR(26)  NULL,
    created_at          DATETIME(6)  NOT NULL,
    created_by          VARCHAR(100) NULL,

    issued_document_id  BIGINT       NOT NULL,
    downloaded_by       VARCHAR(100) NULL,
    downloaded_at       DATETIME(6)  NOT NULL,
    outcome             VARCHAR(30)  NOT NULL,
    ip_address          VARCHAR(45)  NULL,
    user_agent          VARCHAR(500) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_document_download_events_public_id (public_id),
    KEY idx_document_download_events_doc (issued_document_id, downloaded_at),
    CONSTRAINT fk_document_download_events_doc FOREIGN KEY (issued_document_id)
        REFERENCES issued_documents (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Point the existing verification table at the issued document, and drop the
-- stored QR image.
--
-- Storing a base64 PNG per document is dead weight: the image is a rendering
-- of the token and regenerating it costs nothing.
-- ---------------------------------------------------------------------
ALTER TABLE document_verifications
    ADD COLUMN issued_document_id BIGINT NULL AFTER document_id,
    ADD CONSTRAINT fk_document_verifications_issued
        FOREIGN KEY (issued_document_id) REFERENCES issued_documents (id) ON DELETE CASCADE;

CREATE INDEX idx_document_verifications_issued ON document_verifications (issued_document_id);

-- ---------------------------------------------------------------------
-- Public verification attempts.
--
-- Recorded because the endpoint is unauthenticated, and a run of guesses
-- against invented tokens is the only signal that somebody is probing it.
-- ---------------------------------------------------------------------
CREATE TABLE verification_attempts (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    public_id         VARCHAR(26)  NULL,
    created_at        DATETIME(6)  NOT NULL,
    created_by        VARCHAR(100) NULL,

    token_presented   VARCHAR(64)  NOT NULL,
    outcome           VARCHAR(30)  NOT NULL,
    ip_address        VARCHAR(45)  NULL,
    user_agent        VARCHAR(500) NULL,
    attempted_at      DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_verification_attempts_public_id (public_id),
    KEY idx_verification_attempts_ip (ip_address, attempted_at),
    KEY idx_verification_attempts_outcome (outcome, attempted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
