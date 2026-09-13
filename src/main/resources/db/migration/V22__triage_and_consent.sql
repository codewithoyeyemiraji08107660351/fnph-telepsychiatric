-- =====================================================================
-- V22 - triage and consent
--
-- WHY THIS IS NOT A CHECKBOX
--   Patient.consentVersion was a column on the patient row and was removed as
--   inadequate. A patient consented to a consultation in March; that is not
--   consent to one in September, and a system that treats it as one has
--   stopped asking.
--
--   Consent is versioned, and an acceptance names the version, the moment and
--   the address it came from. If the text changes, existing acceptances still
--   point at what was actually agreed to rather than at whatever the document
--   says today.
--
-- THE STOP PATHS ARE THE POINT OF TRIAGE
--   This service excludes emergencies, severe agitation, acute psychosis and
--   immediate risk. Triage exists to find those before a patient pays for a
--   consultation that cannot help them, and the answer that stops the journey
--   is recorded rather than the patient being silently turned away.
--
--   A stopped triage is not a rejection. It is a redirection, and the
--   escalation text that goes with it is configuration so FNPH can change the
--   wording without a release.
--
-- THE QUESTION TEXT IS SEEDED AS A PLACEHOLDER
--   FNPH have to supply the real wording. The five questions below are marked
--   as drafts and the version is 'DRAFT-0'. Replace them before pilot; the
--   structure does not need to change to do it.
-- =====================================================================

CREATE TABLE consent_documents (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    public_id       VARCHAR(26)  NULL,
    created_at      DATETIME(6)  NOT NULL,
    created_by      VARCHAR(100) NULL,
    updated_at      DATETIME(6)  NULL,
    updated_by      VARCHAR(100) NULL,

    version         VARCHAR(30)  NOT NULL,
    audience        VARCHAR(20)  NOT NULL,
    title           VARCHAR(200) NOT NULL,
    body            LONGTEXT     NOT NULL,
    -- A previous version stays readable forever. An acceptance points at the
    -- text that was actually agreed to, not at whatever it says today.
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    effective_from  DATETIME(6)  NULL,
    retired_at      DATETIME(6)  NULL,
    published_by    VARCHAR(100) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_consent_documents_public_id (public_id),
    UNIQUE KEY uk_consent_documents_version (audience, version),
    KEY idx_consent_documents_active (audience, status),
    CONSTRAINT ck_consent_documents_audience CHECK (audience IN ('FNPH_PATIENT','CENTRE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Append-only. What somebody agreed to cannot be edited afterwards.
CREATE TABLE consent_acceptances (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    public_id           VARCHAR(26)  NULL,
    created_at          DATETIME(6)  NOT NULL,
    created_by          VARCHAR(100) NULL,

    consent_document_id BIGINT       NOT NULL,
    consent_version     VARCHAR(30)  NOT NULL,
    patient_id          BIGINT       NULL,
    centre_patient_id   BIGINT       NULL,
    centre_id           BIGINT       NULL,
    accepted_at         DATETIME(6)  NOT NULL,
    accepted_by         VARCHAR(150) NOT NULL,
    -- Who witnessed it, for a centre patient who is in a room with staff
    -- rather than alone with a browser.
    witnessed_by        VARCHAR(150) NULL,
    ip_address          VARCHAR(45)  NULL,
    user_agent          VARCHAR(500) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_consent_acceptances_public_id (public_id),
    KEY idx_consent_acceptances_patient (patient_id, accepted_at),
    KEY idx_consent_acceptances_centre_patient (centre_patient_id, accepted_at),
    CONSTRAINT fk_consent_acceptances_document FOREIGN KEY (consent_document_id)
        REFERENCES consent_documents (id),
    CONSTRAINT fk_consent_acceptances_patient FOREIGN KEY (patient_id) REFERENCES patients (id),
    CONSTRAINT fk_consent_acceptances_centre_patient FOREIGN KEY (centre_patient_id)
        REFERENCES centre_patients (id),
    CONSTRAINT fk_consent_acceptances_centre FOREIGN KEY (centre_id) REFERENCES centres (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Triage
-- ---------------------------------------------------------------------
CREATE TABLE triage_question_sets (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    public_id      VARCHAR(26)  NULL,
    created_at     DATETIME(6)  NOT NULL,
    created_by     VARCHAR(100) NULL,
    updated_at     DATETIME(6)  NULL,
    updated_by     VARCHAR(100) NULL,

    version        VARCHAR(30)  NOT NULL,
    audience       VARCHAR(20)  NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    effective_from DATETIME(6)  NULL,
    retired_at     DATETIME(6)  NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_triage_sets_public_id (public_id),
    UNIQUE KEY uk_triage_sets_version (audience, version),
    KEY idx_triage_sets_active (audience, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE triage_questions (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    public_id      VARCHAR(26)  NULL,
    created_at     DATETIME(6)  NOT NULL,
    created_by     VARCHAR(100) NULL,
    updated_at     DATETIME(6)  NULL,
    updated_by     VARCHAR(100) NULL,

    question_set_id BIGINT      NOT NULL,
    sequence        INT         NOT NULL,
    question_text   TEXT        NOT NULL,
    -- The answer that stops the journey. Stored rather than assumed, because
    -- a question can be worded so that either answer is the dangerous one.
    stop_answer     VARCHAR(10) NOT NULL,
    stop_reason     VARCHAR(200) NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_triage_questions_public_id (public_id),
    UNIQUE KEY uk_triage_questions_sequence (question_set_id, sequence),
    CONSTRAINT fk_triage_questions_set FOREIGN KEY (question_set_id)
        REFERENCES triage_question_sets (id) ON DELETE CASCADE,
    CONSTRAINT ck_triage_stop_answer CHECK (stop_answer IN ('YES','NO'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Append-only. A patient who answered yes to a risk question and later
-- changed it must leave both answers in the record.
CREATE TABLE triage_responses (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    public_id        VARCHAR(26)  NULL,
    created_at       DATETIME(6)  NOT NULL,
    created_by       VARCHAR(100) NULL,

    question_set_id  BIGINT       NOT NULL,
    triage_version   VARCHAR(30)  NOT NULL,
    patient_id       BIGINT       NULL,
    centre_patient_id BIGINT      NULL,
    centre_id        BIGINT       NULL,

    answers_json     TEXT         NOT NULL,
    outcome          VARCHAR(20)  NOT NULL,
    stopped_on_question_id BIGINT NULL,
    stop_reason      VARCHAR(200) NULL,
    escalation_shown TEXT         NULL,
    submitted_at     DATETIME(6)  NOT NULL,
    ip_address       VARCHAR(45)  NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_triage_responses_public_id (public_id),
    KEY idx_triage_responses_patient (patient_id, submitted_at),
    KEY idx_triage_responses_outcome (outcome, submitted_at),
    CONSTRAINT fk_triage_responses_set FOREIGN KEY (question_set_id)
        REFERENCES triage_question_sets (id),
    CONSTRAINT fk_triage_responses_patient FOREIGN KEY (patient_id) REFERENCES patients (id),
    CONSTRAINT fk_triage_responses_centre_patient FOREIGN KEY (centre_patient_id)
        REFERENCES centre_patients (id),
    CONSTRAINT fk_triage_responses_question FOREIGN KEY (stopped_on_question_id)
        REFERENCES triage_questions (id),
    CONSTRAINT ck_triage_outcome CHECK (outcome IN ('PROCEED','STOPPED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- An appointment records which triage and consent it was booked against.
ALTER TABLE appointments
    ADD COLUMN triage_response_id     BIGINT NULL AFTER payment_id,
    ADD COLUMN consent_acceptance_id  BIGINT NULL AFTER triage_response_id,
    ADD CONSTRAINT fk_appointments_triage  FOREIGN KEY (triage_response_id)
        REFERENCES triage_responses (id),
    ADD CONSTRAINT fk_appointments_consent FOREIGN KEY (consent_acceptance_id)
        REFERENCES consent_acceptances (id);

CREATE INDEX idx_appointments_triage ON appointments (triage_response_id);
CREATE INDEX idx_appointments_consent ON appointments (consent_acceptance_id);

ALTER TABLE centre_referrals
    ADD COLUMN consent_acceptance_id BIGINT NULL AFTER consent_accepted_by,
    ADD CONSTRAINT fk_centre_referrals_consent FOREIGN KEY (consent_acceptance_id)
        REFERENCES consent_acceptances (id);

CREATE INDEX idx_centre_referrals_consent ON centre_referrals (consent_acceptance_id);

-- ---------------------------------------------------------------------
-- Placeholder content. FNPH MUST replace this before pilot.
--
-- Seeded as DRAFT-0 and left in DRAFT so nothing can be booked against it by
-- accident: the service refuses to run triage with no PUBLISHED set, which is
-- a loud failure rather than a quiet one.
-- ---------------------------------------------------------------------
INSERT INTO triage_question_sets
    (created_at, created_by, public_id, version, audience, status)
VALUES (UTC_TIMESTAMP(6), 'system', '01TRIAGEDRAFT00000000000A', 'DRAFT-0',
        'FNPH_PATIENT', 'DRAFT');

INSERT INTO triage_questions
    (created_at, created_by, question_set_id, sequence, question_text, stop_answer, stop_reason)
SELECT UTC_TIMESTAMP(6), 'system', s.id, q.seq, q.text, q.stop, q.reason
FROM triage_question_sets s
CROSS JOIN (
    SELECT 1 AS seq,
      'PLACEHOLDER - FNPH to supply. Are you or the patient in immediate danger, or is this an emergency right now?' AS text,
      'YES' AS stop, 'Emergency or immediate danger' AS reason
    UNION ALL SELECT 2,
      'PLACEHOLDER - FNPH to supply. Has the patient had thoughts of harming themselves or someone else in the last 48 hours?',
      'YES', 'Immediate risk of harm'
    UNION ALL SELECT 3,
      'PLACEHOLDER - FNPH to supply. Is the patient severely agitated, or unable to remain safely in one place?',
      'YES', 'Severe agitation'
    UNION ALL SELECT 4,
      'PLACEHOLDER - FNPH to supply. Is the patient experiencing hallucinations or confusion that started suddenly?',
      'YES', 'Possible acute psychosis'
    UNION ALL SELECT 5,
      'PLACEHOLDER - FNPH to supply. Can the patient take part in a video conversation for about thirty minutes in a private place?',
      'NO', 'Cannot participate in a remote consultation'
) q
WHERE s.version = 'DRAFT-0' AND s.audience = 'FNPH_PATIENT';

INSERT INTO consent_documents
    (created_at, created_by, public_id, version, audience, title, body, status)
VALUES (UTC_TIMESTAMP(6), 'system', '01CONSENTDRAFT0000000000A', 'DRAFT-0', 'FNPH_PATIENT',
        'PLACEHOLDER - Telepsychiatry consultation consent',
        'PLACEHOLDER. FNPH must supply the approved consent text before pilot. It should '
        'cover: what a remote consultation is and is not, that the service does not handle '
        'emergencies, what is recorded and who can see it, that sessions are not recorded '
        'unless separately agreed, the fee and that it is not refundable, and how to '
        'withdraw. This record is deliberately left in DRAFT so nothing can be booked '
        'against it.',
        'DRAFT'),
       (UTC_TIMESTAMP(6), 'system', '01CONSENTDRAFT0000000000B', 'DRAFT-0', 'CENTRE',
        'PLACEHOLDER - Centre referral consultation consent',
        'PLACEHOLDER. FNPH must supply the approved text. The centre version is witnessed '
        'by centre staff rather than accepted alone in a browser, and should say so.',
        'DRAFT');
