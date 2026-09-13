-- =====================================================================
-- V18 - filesystem storage
--
-- FNPH have chosen a filesystem rather than object storage. The columns were
-- named for S3, and "bucket" will mislead everyone who reads them for the
-- next few years, so they are renamed now rather than left as archaeology.
--
--   storage_bucket -> storage_area   the logical area: uploads, documents,
--                                    ehr-imports, recordings
--   storage_key    -> storage_path   the path relative to the storage root
--
-- WHAT THIS CHOICE COSTS, WRITTEN DOWN HERE BECAUSE IT IS EASY TO FORGET
--
--   1. The database backup is no longer sufficient on its own. A restore that
--      brings back the database without the files leaves every document row
--      pointing at nothing, and the system will look healthy while every
--      download 404s. Files and database must be backed up together and
--      restored together. scripts/backup.sh is extended for this.
--
--   2. The application can no longer run on two nodes without shared storage.
--      A file written on node A does not exist on node B, so a download routed
--      to the wrong node fails intermittently, which is the hardest class of
--      fault to diagnose. Either run one node, or put the storage root on a
--      shared mount that both nodes see at the same path.
--
--   3. Disk fills. Object storage grows silently; a disk does not. A monitor
--      on free space is now a production requirement, not a nicety.
--
-- INTEGRITY COLUMNS
--   A file on a disk can be truncated by a crash mid-write or corrupted by
--   failing hardware, and neither changes the row that points at it. Every
--   stored object carries its size and SHA-256, verified on read, so a
--   corrupted prescription is detected rather than served.
-- =====================================================================

ALTER TABLE file_uploads
    CHANGE COLUMN storage_bucket storage_area VARCHAR(40)  NOT NULL,
    CHANGE COLUMN storage_key    storage_path VARCHAR(500) NOT NULL;

ALTER TABLE issued_documents
    CHANGE COLUMN storage_bucket storage_area VARCHAR(40)  NULL,
    CHANGE COLUMN storage_key    storage_path VARCHAR(500) NULL,
    ADD COLUMN file_size_bytes BIGINT      NULL AFTER file_checksum,
    ADD COLUMN content_type    VARCHAR(100) NULL AFTER file_size_bytes;

ALTER TABLE recordings
    CHANGE COLUMN storage_bucket storage_area VARCHAR(40)  NULL,
    CHANGE COLUMN storage_key    storage_path VARCHAR(500) NULL;

ALTER TABLE transcripts
    CHANGE COLUMN storage_bucket storage_area VARCHAR(40)  NULL,
    CHANGE COLUMN storage_key    storage_path VARCHAR(500) NULL;

-- ---------------------------------------------------------------------
-- The EHR import file was never retained. It is the evidence behind every
-- patient activated from that snapshot, so on a filesystem there is no reason
-- not to keep it.
-- ---------------------------------------------------------------------
ALTER TABLE ehr_verification_imports
    ADD COLUMN storage_area VARCHAR(40)  NULL AFTER file_size_bytes,
    ADD COLUMN storage_path VARCHAR(500) NULL AFTER storage_area;

-- ---------------------------------------------------------------------
-- Deletion is two-step, and this table is the second step.
--
-- Unlinking a file inside the transaction that marks it deleted loses the
-- file if the transaction then rolls back, and there is no undo on a disk. So
-- the row is marked, the path is queued here, and a sweeper removes it after
-- the transaction has definitely committed.
-- ---------------------------------------------------------------------
CREATE TABLE storage_deletions (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    public_id      VARCHAR(26)  NULL,
    created_at     DATETIME(6)  NOT NULL,
    created_by     VARCHAR(100) NULL,
    updated_at     DATETIME(6)  NULL,
    updated_by     VARCHAR(100) NULL,

    storage_area   VARCHAR(40)  NOT NULL,
    storage_path   VARCHAR(500) NOT NULL,
    reason         VARCHAR(500) NOT NULL,
    requested_at   DATETIME(6)  NOT NULL,
    requested_by   VARCHAR(100) NULL,
    -- Nothing is removed before this moment. A window in which a mistaken
    -- deletion can still be undone, because a disk has no recycle bin.
    eligible_at    DATETIME(6)  NOT NULL,
    deleted_at     DATETIME(6)  NULL,
    attempts       INT          NOT NULL DEFAULT 0,
    last_error     VARCHAR(500) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_storage_deletions_public_id (public_id),
    UNIQUE KEY uk_storage_deletions_path (storage_area, storage_path),
    KEY idx_storage_deletions_pending (deleted_at, eligible_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO system_configuration
    (created_at, created_by, config_key, config_value, value_type, category,
     description, min_value, max_value, allowed_values, requires_governance,
     is_sensitive, effective_from)
VALUES
 (UTC_TIMESTAMP(6),'system','storage_deletion_grace_hours','72','INTEGER','uploads',
  'How long a file sits queued before it is actually removed from disk. A disk has no recycle bin.',
  '0','720',NULL,b'1',b'0',UTC_TIMESTAMP(6)),
 (UTC_TIMESTAMP(6),'system','storage_free_space_warning_percent','20','INTEGER','uploads',
  'Free space below this raises a warning to ICT. Object storage grows silently; a disk does not.',
  '5','50',NULL,b'0',b'0',UTC_TIMESTAMP(6));

INSERT INTO configuration_changes
    (created_at, created_by, configuration_id, config_key, previous_value,
     new_value, reason, changed_by, changed_at, effective_from)
SELECT UTC_TIMESTAMP(6), 'system', c.id, c.config_key, NULL, c.config_value,
       'Initial value seeded in V18', 'system', UTC_TIMESTAMP(6), c.effective_from
FROM system_configuration c
WHERE c.config_key LIKE 'storage_%'
  AND NOT EXISTS (SELECT 1 FROM configuration_changes h WHERE h.config_key = c.config_key);
