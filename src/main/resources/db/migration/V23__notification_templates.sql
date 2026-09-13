-- =====================================================================
-- V23 - notification templates
--
-- notification.manage_templates existed with nothing behind it. The wording of
-- a message to a patient is the kind of thing FNPH will want to change after
-- the first week of a pilot, and a redeploy to fix a sentence is a redeploy
-- that does not happen.
--
-- Only in-app and email subject and body live here. The account emails stay as
-- Thymeleaf files, because those carry layout and a hospital letterhead rather
-- than a sentence.
--
-- A template with no row falls back to the wording in the code. That is
-- deliberate: an empty table must not mean an empty notification.
-- =====================================================================

CREATE TABLE notification_templates (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    public_id         VARCHAR(26)  NULL,
    created_at        DATETIME(6)  NOT NULL,
    created_by        VARCHAR(100) NULL,
    updated_at        DATETIME(6)  NULL,
    updated_by        VARCHAR(100) NULL,

    notification_type VARCHAR(50)  NOT NULL,
    channel           VARCHAR(20)  NOT NULL,
    subject           VARCHAR(200) NOT NULL,
    body              TEXT         NOT NULL,
    is_active         BIT(1)       NOT NULL DEFAULT b'1',

    -- Never true for a patient-facing message. A notification arrives on a
    -- phone that may be on a shared desk, so it says a document is ready and
    -- not what it is for.
    contains_clinical_detail BIT(1) NOT NULL DEFAULT b'0',

    updated_reason    VARCHAR(500) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_templates_public_id (public_id),
    UNIQUE KEY uk_notification_templates_type (notification_type, channel),
    KEY idx_notification_templates_active (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Broadcasts, so a sent announcement is a record rather than a log line.
CREATE TABLE notification_broadcasts (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    public_id      VARCHAR(26)  NULL,
    created_at     DATETIME(6)  NOT NULL,
    created_by     VARCHAR(100) NULL,
    updated_at     DATETIME(6)  NULL,
    updated_by     VARCHAR(100) NULL,

    subject        VARCHAR(200) NOT NULL,
    body           TEXT         NOT NULL,
    target_role_id BIGINT       NULL,
    centre_id      BIGINT       NULL,
    -- Null target role and null centre means everyone, which is the setting
    -- most likely to be chosen by mistake, so it is recorded plainly.
    sent_by        VARCHAR(100) NOT NULL,
    sent_at        DATETIME(6)  NOT NULL,
    recipient_count INT         NOT NULL DEFAULT 0,
    reason         VARCHAR(500) NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_broadcasts_public_id (public_id),
    KEY idx_notification_broadcasts_sent (sent_at),
    CONSTRAINT fk_notification_broadcasts_role FOREIGN KEY (target_role_id) REFERENCES roles (id),
    CONSTRAINT fk_notification_broadcasts_centre FOREIGN KEY (centre_id) REFERENCES centres (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
