-- =====================================================================
-- V13 - video consultation
--
-- WHAT IS PERSISTED, AND WHAT IS NOT
--   The room reference, the participant tokens, who joined when, how the call
--   behaved, and how it ended. No media. Recording is off and stays off until
--   FNPH approve consent, retention, access, data location, deletion and
--   incident response.
--
-- THE SERVER OWNS THE CLOCK
--   The client shows a countdown; the server decides when the session is over.
--   A browser timer is a suggestion, and a clinician whose tab has been open
--   since this morning has a suggestion that is hours wrong.
--
-- A FIXED SLOT END
--   scheduled_end_at is set from the slot, not from when someone joined. A
--   patient arriving five minutes late gets twenty-five minutes, not thirty.
--   The next patient's slot is the reason: extending one session shortens the
--   next, and the person who loses the time had nothing to do with the delay.
--
-- PARTICIPANT TOKENS ARE STORED HASHED
--   A Daily meeting token is a bearer credential for a live clinical
--   consultation. Anyone holding it joins the call. Only the hash is stored,
--   so a leak of this table through a backup or a log yields nothing usable.
-- =====================================================================

ALTER TABLE consultations
    ADD COLUMN room_name         VARCHAR(120) NULL AFTER room_provider_id,
    ADD COLUMN room_url          VARCHAR(300) NULL AFTER room_name,
    ADD COLUMN room_expires_at   DATETIME(6)  NULL AFTER room_url,
    ADD COLUMN room_created_at   DATETIME(6)  NULL AFTER room_expires_at,
    ADD COLUMN room_deleted_at   DATETIME(6)  NULL AFTER room_created_at,
    -- Counted from the fixed slot end, never from first join.
    ADD COLUMN remaining_seconds INT          NULL AFTER warning_two_sent_at,
    ADD COLUMN connection_issues INT          NOT NULL DEFAULT 0 AFTER remaining_seconds;

CREATE UNIQUE INDEX uk_consultations_room_name ON consultations (room_name);

ALTER TABLE centre_consultations
    ADD COLUMN room_name         VARCHAR(120) NULL AFTER room_provider_id,
    ADD COLUMN room_url          VARCHAR(300) NULL AFTER room_name,
    ADD COLUMN room_expires_at   DATETIME(6)  NULL AFTER room_url,
    ADD COLUMN room_created_at   DATETIME(6)  NULL AFTER room_expires_at,
    ADD COLUMN room_deleted_at   DATETIME(6)  NULL AFTER room_created_at,
    ADD COLUMN remaining_seconds INT          NULL AFTER warning_two_sent_at,
    ADD COLUMN connection_issues INT          NOT NULL DEFAULT 0 AFTER remaining_seconds;

CREATE UNIQUE INDEX uk_centre_consultations_room_name ON centre_consultations (room_name);

-- ---------------------------------------------------------------------
-- Participant tokens
--
-- One token, one participant, one session. Short-lived and single-use so a
-- forwarded join link is worth nothing after the call.
-- ---------------------------------------------------------------------
CREATE TABLE participant_tokens (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    public_id               VARCHAR(26)  NULL,
    created_at              DATETIME(6)  NOT NULL,
    created_by              VARCHAR(100) NULL,
    updated_at              DATETIME(6)  NULL,
    updated_by              VARCHAR(100) NULL,

    consultation_id         BIGINT       NULL,
    centre_consultation_id  BIGINT       NULL,

    participant_role        VARCHAR(20)  NOT NULL,
    user_id                 BIGINT       NULL,
    patient_id              BIGINT       NULL,
    centre_id               BIGINT       NULL,

    -- SHA-256 only. The token itself is a bearer credential for a live
    -- clinical consultation and is never stored.
    token_hash              VARCHAR(64)  NOT NULL,
    display_name            VARCHAR(120) NOT NULL,

    -- Not valid before the join window opens, and dead at the slot end.
    not_before              DATETIME(6)  NOT NULL,
    expires_at              DATETIME(6)  NOT NULL,
    issued_at               DATETIME(6)  NOT NULL,
    used_at                 DATETIME(6)  NULL,
    revoked_at              DATETIME(6)  NULL,
    revoked_reason          VARCHAR(200) NULL,
    issued_ip               VARCHAR(45)  NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_participant_tokens_public_id (public_id),
    UNIQUE KEY uk_participant_tokens_hash (token_hash),
    KEY idx_participant_tokens_consultation (consultation_id, participant_role),
    KEY idx_participant_tokens_centre (centre_consultation_id, participant_role),
    KEY idx_participant_tokens_expiry (expires_at, revoked_at),
    CONSTRAINT fk_participant_tokens_consultation FOREIGN KEY (consultation_id)
        REFERENCES consultations (id) ON DELETE CASCADE,
    CONSTRAINT fk_participant_tokens_centre_consultation FOREIGN KEY (centre_consultation_id)
        REFERENCES centre_consultations (id) ON DELETE CASCADE,
    CONSTRAINT fk_participant_tokens_user    FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_participant_tokens_patient FOREIGN KEY (patient_id) REFERENCES patients (id),
    CONSTRAINT fk_participant_tokens_centre  FOREIGN KEY (centre_id) REFERENCES centres (id),

    CONSTRAINT ck_participant_tokens_one_session CHECK (
        (consultation_id IS NOT NULL AND centre_consultation_id IS NULL)
     OR (consultation_id IS NULL AND centre_consultation_id IS NOT NULL)
    ),
    CONSTRAINT ck_participant_tokens_window CHECK (expires_at > not_before)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Attendance
--
-- Append-only. Who joined, when, and how the call ended is the evidence
-- behind a no-show, a disputed attendance, and any question about whether a
-- consultation actually took place.
--
-- provider_event_id is unique so a repeated Daily webhook cannot record the
-- same join twice.
-- ---------------------------------------------------------------------
CREATE TABLE attendance_events (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    public_id              VARCHAR(26)  NULL,
    created_at             DATETIME(6)  NOT NULL,
    created_by             VARCHAR(100) NULL,

    consultation_id        BIGINT       NULL,
    centre_consultation_id BIGINT       NULL,
    participant_role       VARCHAR(20)  NOT NULL,
    event_type             VARCHAR(30)  NOT NULL,
    modality               VARCHAR(20)  NULL,
    occurred_at            DATETIME(6)  NOT NULL,
    provider_event_id      VARCHAR(150) NULL,
    provider_session_id    VARCHAR(150) NULL,
    details                VARCHAR(500) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_attendance_events_public_id (public_id),
    UNIQUE KEY uk_attendance_events_provider (provider_event_id),
    KEY idx_attendance_events_consultation (consultation_id, occurred_at),
    KEY idx_attendance_events_centre (centre_consultation_id, occurred_at),
    CONSTRAINT fk_attendance_events_consultation FOREIGN KEY (consultation_id)
        REFERENCES consultations (id) ON DELETE CASCADE,
    CONSTRAINT fk_attendance_events_centre FOREIGN KEY (centre_consultation_id)
        REFERENCES centre_consultations (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Connection quality
--
-- Recorded because "the call kept dropping" is the most common complaint
-- about telemedicine anywhere, and without numbers it is one person's word
-- against another's about whether the service or the line was at fault.
-- ---------------------------------------------------------------------
CREATE TABLE connection_quality_events (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    public_id              VARCHAR(26)  NULL,
    created_at             DATETIME(6)  NOT NULL,
    created_by             VARCHAR(100) NULL,

    consultation_id        BIGINT       NULL,
    centre_consultation_id BIGINT       NULL,
    participant_role       VARCHAR(20)  NOT NULL,
    round_trip_ms          INT          NULL,
    packet_loss_percent    DECIMAL(5,2) NULL,
    video_receive_quality  VARCHAR(20)  NULL,
    recorded_at            DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_connection_quality_public_id (public_id),
    KEY idx_connection_quality_consultation (consultation_id, recorded_at),
    CONSTRAINT fk_connection_quality_consultation FOREIGN KEY (consultation_id)
        REFERENCES consultations (id) ON DELETE CASCADE,
    CONSTRAINT fk_connection_quality_centre FOREIGN KEY (centre_consultation_id)
        REFERENCES centre_consultations (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Recording and transcript
--
-- Modelled now, disabled by default, and the room is created with recording
-- switched off at the provider unless configuration says otherwise. Building
-- the consent, retention and deletion columns up front means turning it on
-- later is a governance decision rather than a schema change made in a hurry.
-- ---------------------------------------------------------------------
CREATE TABLE recordings (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    public_id              VARCHAR(26)  NULL,
    created_at             DATETIME(6)  NOT NULL,
    created_by             VARCHAR(100) NULL,
    updated_at             DATETIME(6)  NULL,
    updated_by             VARCHAR(100) NULL,

    consultation_id        BIGINT       NULL,
    centre_consultation_id BIGINT       NULL,
    provider_recording_id  VARCHAR(150) NULL,
    storage_bucket         VARCHAR(100) NULL,
    storage_key            VARCHAR(500) NULL,

    -- Which consent version every participant accepted. Without this the
    -- recording is evidence of a conversation nobody agreed to record.
    consent_acceptance_id  BIGINT       NULL,
    all_participants_notified BIT(1)    NOT NULL DEFAULT b'0',

    started_at             DATETIME(6)  NULL,
    stopped_at             DATETIME(6)  NULL,
    duration_seconds       INT          NULL,
    retention_expires_at   DATETIME(6)  NULL,
    deleted_at             DATETIME(6)  NULL,
    deleted_by             VARCHAR(100) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_recordings_public_id (public_id),
    KEY idx_recordings_consultation (consultation_id),
    KEY idx_recordings_retention (retention_expires_at, deleted_at),
    CONSTRAINT fk_recordings_consultation FOREIGN KEY (consultation_id)
        REFERENCES consultations (id),
    CONSTRAINT fk_recordings_centre FOREIGN KEY (centre_consultation_id)
        REFERENCES centre_consultations (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE transcripts (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    public_id              VARCHAR(26)  NULL,
    created_at             DATETIME(6)  NOT NULL,
    created_by             VARCHAR(100) NULL,
    updated_at             DATETIME(6)  NULL,
    updated_by             VARCHAR(100) NULL,

    consultation_id        BIGINT       NULL,
    centre_consultation_id BIGINT       NULL,
    recording_id           BIGINT       NULL,
    storage_bucket         VARCHAR(100) NULL,
    storage_key            VARCHAR(500) NULL,
    status                 VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',

    -- A transcript is an assistive draft. Nothing here is clinical until a
    -- clinician has read it and said so.
    clinician_reviewed_at  DATETIME(6)  NULL,
    clinician_reviewed_by  VARCHAR(100) NULL,
    retained_clinically    BIT(1)       NOT NULL DEFAULT b'0',

    PRIMARY KEY (id),
    UNIQUE KEY uk_transcripts_public_id (public_id),
    KEY idx_transcripts_consultation (consultation_id),
    CONSTRAINT fk_transcripts_consultation FOREIGN KEY (consultation_id)
        REFERENCES consultations (id),
    CONSTRAINT fk_transcripts_centre FOREIGN KEY (centre_consultation_id)
        REFERENCES centre_consultations (id),
    CONSTRAINT fk_transcripts_recording FOREIGN KEY (recording_id) REFERENCES recordings (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Recording stays off. Turning it on is a governance decision, recorded with
-- a reason like any other configuration change.
UPDATE system_configuration SET config_value = 'false' WHERE config_key = 'recording_enabled';
