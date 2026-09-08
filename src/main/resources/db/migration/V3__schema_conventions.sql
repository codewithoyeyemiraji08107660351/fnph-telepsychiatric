-- =====================================================================
-- V3 — schema conventions applied to every table
--
-- Adds the six columns that every table in this schema carries from here
-- on: id, public_id, created_at, created_by, updated_at, updated_by.
-- V1 shipped four of them; this adds public_id and the two _by columns.
--
-- ON public_id
--   The primary key stays BIGINT auto-increment because InnoDB clusters on
--   it. A sequential integer must not appear in a URL, though: it leaks the
--   record count and invites enumeration. public_id is a 26-character ULID
--   and is the only identifier any external surface ever sees.
--
--   It is declared NULL with a UNIQUE index rather than NOT NULL. MySQL
--   permits multiple NULLs under a unique index, so the column can be
--   introduced against a table that already holds rows without inventing
--   values for them. The application populates it on every insert via
--   @PrePersist, and SchemaConventionsTest asserts no persisted row is ever
--   null. A later migration can tighten it to NOT NULL once any legacy rows
--   are backfilled.
--
-- ON append-only tables
--   audit_logs and wallet_transactions get public_id and created_by only.
--   They have no updated_at by design and must not gain updated_by.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Mutable tables: public_id, created_by, updated_by
-- ---------------------------------------------------------------------
ALTER TABLE centres                   ADD COLUMN public_id VARCHAR(26) NULL AFTER id;
ALTER TABLE patients                  ADD COLUMN public_id VARCHAR(26) NULL AFTER id;
ALTER TABLE users                     ADD COLUMN public_id VARCHAR(26) NULL AFTER id;
ALTER TABLE centre_staff              ADD COLUMN public_id VARCHAR(26) NULL AFTER id;
ALTER TABLE centre_patients           ADD COLUMN public_id VARCHAR(26) NULL AFTER id;
ALTER TABLE appointments              ADD COLUMN public_id VARCHAR(26) NULL AFTER id;
ALTER TABLE centre_appointments       ADD COLUMN public_id VARCHAR(26) NULL AFTER id;
ALTER TABLE vitals                    ADD COLUMN public_id VARCHAR(26) NULL AFTER id;
ALTER TABLE centre_vitals             ADD COLUMN public_id VARCHAR(26) NULL AFTER id;
ALTER TABLE consultations             ADD COLUMN public_id VARCHAR(26) NULL AFTER id;
ALTER TABLE centre_consultations      ADD COLUMN public_id VARCHAR(26) NULL AFTER id;
ALTER TABLE consultation_notes        ADD COLUMN public_id VARCHAR(26) NULL AFTER id;
ALTER TABLE centre_consultation_notes ADD COLUMN public_id VARCHAR(26) NULL AFTER id;
ALTER TABLE prescriptions             ADD COLUMN public_id VARCHAR(26) NULL AFTER id;
ALTER TABLE prescription_items        ADD COLUMN public_id VARCHAR(26) NULL AFTER id;
ALTER TABLE investigations            ADD COLUMN public_id VARCHAR(26) NULL AFTER id;
ALTER TABLE investigation_items       ADD COLUMN public_id VARCHAR(26) NULL AFTER id;
ALTER TABLE follow_ups                ADD COLUMN public_id VARCHAR(26) NULL AFTER id;
ALTER TABLE payments                  ADD COLUMN public_id VARCHAR(26) NULL AFTER id;
ALTER TABLE wallets                   ADD COLUMN public_id VARCHAR(26) NULL AFTER id;
ALTER TABLE document_verifications    ADD COLUMN public_id VARCHAR(26) NULL AFTER id;
ALTER TABLE file_uploads              ADD COLUMN public_id VARCHAR(26) NULL AFTER id;
ALTER TABLE notifications             ADD COLUMN public_id VARCHAR(26) NULL AFTER id;
ALTER TABLE notification_preferences  ADD COLUMN public_id VARCHAR(26) NULL AFTER id;

-- Append-only tables get public_id too.
ALTER TABLE audit_logs                ADD COLUMN public_id VARCHAR(26) NULL AFTER id;
ALTER TABLE wallet_transactions       ADD COLUMN public_id VARCHAR(26) NULL AFTER id;

-- ---------------------------------------------------------------------
-- created_by and updated_by
--
-- centres.created_by already exists from V1 as VARCHAR(255) holding a
-- domain value. It means exactly what the convention column means, so it is
-- narrowed rather than duplicated.
-- ---------------------------------------------------------------------
ALTER TABLE centres MODIFY COLUMN created_by VARCHAR(100) NULL;
ALTER TABLE centres ADD COLUMN updated_by VARCHAR(100) NULL AFTER updated_at;

ALTER TABLE patients                  ADD COLUMN created_by VARCHAR(100) NULL AFTER created_at, ADD COLUMN updated_by VARCHAR(100) NULL AFTER updated_at;
ALTER TABLE users                     ADD COLUMN created_by VARCHAR(100) NULL AFTER created_at, ADD COLUMN updated_by VARCHAR(100) NULL AFTER updated_at;
ALTER TABLE centre_staff              ADD COLUMN created_by VARCHAR(100) NULL AFTER created_at, ADD COLUMN updated_by VARCHAR(100) NULL AFTER updated_at;
ALTER TABLE centre_patients           ADD COLUMN created_by VARCHAR(100) NULL AFTER created_at, ADD COLUMN updated_by VARCHAR(100) NULL AFTER updated_at;
ALTER TABLE appointments              ADD COLUMN created_by VARCHAR(100) NULL AFTER created_at, ADD COLUMN updated_by VARCHAR(100) NULL AFTER updated_at;
ALTER TABLE centre_appointments       ADD COLUMN created_by VARCHAR(100) NULL AFTER created_at, ADD COLUMN updated_by VARCHAR(100) NULL AFTER updated_at;
ALTER TABLE vitals                    ADD COLUMN created_by VARCHAR(100) NULL AFTER created_at, ADD COLUMN updated_by VARCHAR(100) NULL AFTER updated_at;
ALTER TABLE centre_vitals             ADD COLUMN created_by VARCHAR(100) NULL AFTER created_at, ADD COLUMN updated_by VARCHAR(100) NULL AFTER updated_at;
ALTER TABLE consultations             ADD COLUMN created_by VARCHAR(100) NULL AFTER created_at, ADD COLUMN updated_by VARCHAR(100) NULL AFTER updated_at;
ALTER TABLE centre_consultations      ADD COLUMN created_by VARCHAR(100) NULL AFTER created_at, ADD COLUMN updated_by VARCHAR(100) NULL AFTER updated_at;
ALTER TABLE consultation_notes        ADD COLUMN created_by VARCHAR(100) NULL AFTER created_at, ADD COLUMN updated_by VARCHAR(100) NULL AFTER updated_at;
ALTER TABLE centre_consultation_notes ADD COLUMN created_by VARCHAR(100) NULL AFTER created_at, ADD COLUMN updated_by VARCHAR(100) NULL AFTER updated_at;
ALTER TABLE prescriptions             ADD COLUMN created_by VARCHAR(100) NULL AFTER created_at, ADD COLUMN updated_by VARCHAR(100) NULL AFTER updated_at;
ALTER TABLE prescription_items        ADD COLUMN created_by VARCHAR(100) NULL AFTER created_at, ADD COLUMN updated_by VARCHAR(100) NULL AFTER updated_at;
ALTER TABLE investigations            ADD COLUMN created_by VARCHAR(100) NULL AFTER created_at, ADD COLUMN updated_by VARCHAR(100) NULL AFTER updated_at;
ALTER TABLE investigation_items       ADD COLUMN created_by VARCHAR(100) NULL AFTER created_at, ADD COLUMN updated_by VARCHAR(100) NULL AFTER updated_at;
ALTER TABLE follow_ups                ADD COLUMN created_by VARCHAR(100) NULL AFTER created_at, ADD COLUMN updated_by VARCHAR(100) NULL AFTER updated_at;
ALTER TABLE payments                  ADD COLUMN created_by VARCHAR(100) NULL AFTER created_at, ADD COLUMN updated_by VARCHAR(100) NULL AFTER updated_at;
ALTER TABLE wallets                   ADD COLUMN created_by VARCHAR(100) NULL AFTER created_at, ADD COLUMN updated_by VARCHAR(100) NULL AFTER updated_at;
ALTER TABLE document_verifications    ADD COLUMN created_by VARCHAR(100) NULL AFTER created_at, ADD COLUMN updated_by VARCHAR(100) NULL AFTER updated_at;
ALTER TABLE file_uploads              ADD COLUMN created_by VARCHAR(100) NULL AFTER created_at, ADD COLUMN updated_by VARCHAR(100) NULL AFTER updated_at;
ALTER TABLE notifications             ADD COLUMN created_by VARCHAR(100) NULL AFTER created_at, ADD COLUMN updated_by VARCHAR(100) NULL AFTER updated_at;
ALTER TABLE notification_preferences  ADD COLUMN created_by VARCHAR(100) NULL AFTER created_at, ADD COLUMN updated_by VARCHAR(100) NULL AFTER updated_at;

-- Append-only: created_by only, never updated_by.
ALTER TABLE audit_logs          ADD COLUMN created_by VARCHAR(100) NULL AFTER created_at;
ALTER TABLE wallet_transactions ADD COLUMN created_by VARCHAR(100) NULL AFTER created_at;

-- ---------------------------------------------------------------------
-- posted_at and posted_by on the ledger duplicated created_at and
-- created_by. On an append-only table the write IS the posting, so two
-- columns for one fact is two columns that can disagree.
-- ---------------------------------------------------------------------
UPDATE wallet_transactions SET created_by = posted_by WHERE posted_by IS NOT NULL;
ALTER TABLE wallet_transactions DROP COLUMN posted_at;
ALTER TABLE wallet_transactions DROP COLUMN posted_by;

-- ---------------------------------------------------------------------
-- Unique indexes on public_id. Created after the columns so a single
-- failure names the table it came from.
-- ---------------------------------------------------------------------
CREATE UNIQUE INDEX uk_centres_public_id                   ON centres (public_id);
CREATE UNIQUE INDEX uk_patients_public_id                  ON patients (public_id);
CREATE UNIQUE INDEX uk_users_public_id                     ON users (public_id);
CREATE UNIQUE INDEX uk_centre_staff_public_id              ON centre_staff (public_id);
CREATE UNIQUE INDEX uk_centre_patients_public_id           ON centre_patients (public_id);
CREATE UNIQUE INDEX uk_appointments_public_id              ON appointments (public_id);
CREATE UNIQUE INDEX uk_centre_appointments_public_id       ON centre_appointments (public_id);
CREATE UNIQUE INDEX uk_vitals_public_id                    ON vitals (public_id);
CREATE UNIQUE INDEX uk_centre_vitals_public_id             ON centre_vitals (public_id);
CREATE UNIQUE INDEX uk_consultations_public_id             ON consultations (public_id);
CREATE UNIQUE INDEX uk_centre_consultations_public_id      ON centre_consultations (public_id);
CREATE UNIQUE INDEX uk_consultation_notes_public_id        ON consultation_notes (public_id);
CREATE UNIQUE INDEX uk_centre_consult_notes_public_id      ON centre_consultation_notes (public_id);
CREATE UNIQUE INDEX uk_prescriptions_public_id             ON prescriptions (public_id);
CREATE UNIQUE INDEX uk_prescription_items_public_id        ON prescription_items (public_id);
CREATE UNIQUE INDEX uk_investigations_public_id            ON investigations (public_id);
CREATE UNIQUE INDEX uk_investigation_items_public_id       ON investigation_items (public_id);
CREATE UNIQUE INDEX uk_follow_ups_public_id                ON follow_ups (public_id);
CREATE UNIQUE INDEX uk_payments_public_id                  ON payments (public_id);
CREATE UNIQUE INDEX uk_wallets_public_id                   ON wallets (public_id);
CREATE UNIQUE INDEX uk_document_verifications_public_id    ON document_verifications (public_id);
CREATE UNIQUE INDEX uk_file_uploads_public_id              ON file_uploads (public_id);
CREATE UNIQUE INDEX uk_notifications_public_id             ON notifications (public_id);
CREATE UNIQUE INDEX uk_notification_prefs_public_id        ON notification_preferences (public_id);
CREATE UNIQUE INDEX uk_audit_logs_public_id                ON audit_logs (public_id);
CREATE UNIQUE INDEX uk_wallet_transactions_public_id       ON wallet_transactions (public_id);
