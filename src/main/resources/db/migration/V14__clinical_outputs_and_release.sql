-- =====================================================================
-- V14 - professional review, release bundle, clinical note amendment
--
-- THE ONE-WAY RULE
--   Pharmacy and laboratory reviews travel forward to the Hub Coordinator.
--   They never return electronically to the doctor. The specification says so,
--   and the doctor prototype states plainly that nothing comes back through
--   the system.
--
--   That leaves a real gap, and it is answered here rather than ignored: if a
--   pharmacist finds a dosing error, the clarification happens in the
--   multidisciplinary team, and the correction is a NEW prescription that
--   supersedes the old one, authored by the doctor. There is no edit path and
--   no return edge, because either would let a prescription change without the
--   prescriber deciding it.
--
-- WHY "NOT REQUIRED" IS A STATE
--   Most consultations produce no investigation request. Without an explicit
--   not-required state, the release check cannot tell "the doctor decided none
--   was needed" from "the doctor has not got to it yet", so every such bundle
--   would sit blocked forever waiting for a document nobody intends to write.
--
-- WHY RELEASE IS ALL OR NOTHING
--   The Hub Coordinator releases the whole bundle at once. A partial release
--   sends a patient a prescription while the investigation request is still
--   being reviewed, and they act on half their care plan.
--
-- SIGNING IS IRREVERSIBLE
--   A signed clinical note is not editable. An amendment is a new version
--   pointing at the one it supersedes, so the record shows what was written
--   first and what replaced it. Editing in place destroys exactly the evidence
--   an investigation would want.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Professional review
-- ---------------------------------------------------------------------
CREATE TABLE professional_reviews (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    public_id              VARCHAR(26)  NULL,
    created_at             DATETIME(6)  NOT NULL,
    created_by             VARCHAR(100) NULL,
    updated_at             DATETIME(6)  NULL,
    updated_by             VARCHAR(100) NULL,

    review_type            VARCHAR(20)  NOT NULL,
    prescription_id        BIGINT       NULL,
    investigation_id       BIGINT       NULL,

    reviewer_id            BIGINT       NULL,
    assigned_at            DATETIME(6)  NOT NULL,
    opened_at              DATETIME(6)  NULL,
    submitted_at           DATETIME(6)  NULL,

    outcome                VARCHAR(20)  NULL,
    notes                  TEXT         NULL,

    -- Where a reviewer records a concern. It reaches the Hub Coordinator, not
    -- the doctor: the conversation happens in the multidisciplinary team and
    -- any correction is doctor-authored.
    query_raised           BIT(1)       NOT NULL DEFAULT b'0',
    query_detail           TEXT         NULL,
    submitted_to_hub_at    DATETIME(6)  NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_professional_reviews_public_id (public_id),
    UNIQUE KEY uk_professional_reviews_prescription (prescription_id, review_type),
    UNIQUE KEY uk_professional_reviews_investigation (investigation_id, review_type),
    KEY idx_professional_reviews_reviewer (reviewer_id, submitted_at),
    KEY idx_professional_reviews_queue (review_type, submitted_at),
    CONSTRAINT fk_professional_reviews_prescription  FOREIGN KEY (prescription_id) REFERENCES prescriptions (id),
    CONSTRAINT fk_professional_reviews_investigation FOREIGN KEY (investigation_id) REFERENCES investigations (id),
    CONSTRAINT fk_professional_reviews_reviewer      FOREIGN KEY (reviewer_id) REFERENCES users (id),

    -- A review is of exactly one document.
    CONSTRAINT ck_professional_reviews_one_subject CHECK (
        (prescription_id IS NOT NULL AND investigation_id IS NULL)
     OR (prescription_id IS NULL AND investigation_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Release bundle
-- ---------------------------------------------------------------------
CREATE TABLE release_bundles (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    public_id              VARCHAR(26)  NULL,
    created_at             DATETIME(6)  NOT NULL,
    created_by             VARCHAR(100) NULL,
    updated_at             DATETIME(6)  NULL,
    updated_by             VARCHAR(100) NULL,

    appointment_id         BIGINT       NULL,
    centre_appointment_id  BIGINT       NULL,
    centre_id              BIGINT       NULL,

    status                 VARCHAR(20)  NOT NULL DEFAULT 'INCOMPLETE',
    blocked_reason         VARCHAR(500) NULL,
    released_by            VARCHAR(100) NULL,
    released_at            DATETIME(6)  NULL,
    release_notes          VARCHAR(500) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_release_bundles_public_id (public_id),
    UNIQUE KEY uk_release_bundles_appointment (appointment_id),
    UNIQUE KEY uk_release_bundles_centre_appointment (centre_appointment_id),
    KEY idx_release_bundles_status (status, created_at),
    KEY idx_release_bundles_centre (centre_id),
    CONSTRAINT fk_release_bundles_appointment FOREIGN KEY (appointment_id) REFERENCES appointments (id),
    CONSTRAINT fk_release_bundles_centre_appointment FOREIGN KEY (centre_appointment_id) REFERENCES centre_appointments (id),
    CONSTRAINT fk_release_bundles_centre FOREIGN KEY (centre_id) REFERENCES centres (id),

    CONSTRAINT ck_release_bundles_one_pathway CHECK (
        (appointment_id IS NOT NULL AND centre_appointment_id IS NULL)
     OR (appointment_id IS NULL AND centre_appointment_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- One row per required component. Completeness becomes a query rather than a
-- hand-written rule repeated per case.
CREATE TABLE release_bundle_components (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    public_id         VARCHAR(26)  NULL,
    created_at        DATETIME(6)  NOT NULL,
    created_by        VARCHAR(100) NULL,
    updated_at        DATETIME(6)  NULL,
    updated_by        VARCHAR(100) NULL,

    bundle_id         BIGINT       NOT NULL,
    component_type    VARCHAR(30)  NOT NULL,
    component_id      BIGINT       NULL,

    is_required       BIT(1)       NOT NULL DEFAULT b'1',
    is_complete       BIT(1)       NOT NULL DEFAULT b'0',

    -- The doctor decided none was needed. Distinct from "not done yet", which
    -- is what stops a bundle sitting blocked forever waiting for a document
    -- nobody intends to write.
    not_required      BIT(1)       NOT NULL DEFAULT b'0',
    not_required_reason VARCHAR(500) NULL,
    blocked_reason    VARCHAR(500) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_release_bundle_components_public_id (public_id),
    UNIQUE KEY uk_release_bundle_components_type (bundle_id, component_type),
    CONSTRAINT fk_release_bundle_components_bundle FOREIGN KEY (bundle_id)
        REFERENCES release_bundles (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Clinical note amendment
--
-- A signed note is not editable. An amendment is a new version pointing at
-- what it supersedes.
-- ---------------------------------------------------------------------
ALTER TABLE consultation_notes
    ADD COLUMN version           INT          NOT NULL DEFAULT 1 AFTER clinical_note,
    ADD COLUMN supersedes_id     BIGINT       NULL AFTER version,
    ADD COLUMN superseded_at     DATETIME(6)  NULL AFTER supersedes_id,
    ADD COLUMN amendment_reason  VARCHAR(500) NULL AFTER superseded_at;

CREATE INDEX idx_consultation_notes_supersedes ON consultation_notes (supersedes_id);

ALTER TABLE centre_consultation_notes
    ADD COLUMN version           INT          NOT NULL DEFAULT 1 AFTER clinical_note,
    ADD COLUMN supersedes_id     BIGINT       NULL AFTER version,
    ADD COLUMN superseded_at     DATETIME(6)  NULL AFTER supersedes_id,
    ADD COLUMN amendment_reason  VARCHAR(500) NULL AFTER superseded_at;

CREATE INDEX idx_centre_notes_supersedes ON centre_consultation_notes (supersedes_id);

-- ---------------------------------------------------------------------
-- The multidisciplinary team assignment carries onto the clinical output, so
-- a review lands with the person the Hub Coordinator assigned rather than in
-- a shared queue anyone can pick from.
-- ---------------------------------------------------------------------
ALTER TABLE prescriptions
    ADD COLUMN bundle_id BIGINT NULL AFTER centre_id,
    ADD CONSTRAINT fk_prescriptions_bundle FOREIGN KEY (bundle_id) REFERENCES release_bundles (id);
CREATE INDEX idx_prescriptions_bundle ON prescriptions (bundle_id);

ALTER TABLE investigations
    ADD COLUMN bundle_id BIGINT NULL AFTER centre_id,
    ADD CONSTRAINT fk_investigations_bundle FOREIGN KEY (bundle_id) REFERENCES release_bundles (id);
CREATE INDEX idx_investigations_bundle ON investigations (bundle_id);

ALTER TABLE follow_ups
    ADD COLUMN bundle_id BIGINT NULL AFTER centre_id,
    ADD CONSTRAINT fk_follow_ups_bundle FOREIGN KEY (bundle_id) REFERENCES release_bundles (id);
CREATE INDEX idx_follow_ups_bundle ON follow_ups (bundle_id);
