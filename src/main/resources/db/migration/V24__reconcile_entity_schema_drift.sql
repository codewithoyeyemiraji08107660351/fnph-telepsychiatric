-- V24__reconcile_entity_schema_drift.sql
--
-- The migration history had fallen behind the entity model. Confirmed by
-- diffing Hibernate's generated schema against information_schema on a
-- database built from V1-V23 alone. Six columns, two tables.

-- patients: address and email were on the entity from the start and were
-- never added to the schema. V1 created the table without them and no later
-- migration filled the gap.
ALTER TABLE patients
    ADD COLUMN email   VARCHAR(50) NULL AFTER phone_number,
    ADD COLUMN address TEXT        NULL AFTER email;

-- file_uploads: V18 created the table without the standard soft-delete block
-- that V3 established for every other soft-deletable table.
ALTER TABLE file_uploads
    ADD COLUMN deleted        BIT(1)       NOT NULL DEFAULT b'0' AFTER updated_by,
    ADD COLUMN deleted_at     DATETIME(6)  NULL                  AFTER deleted,
    ADD COLUMN deleted_by     VARCHAR(100) NULL                  AFTER deleted_at,
    ADD COLUMN deleted_reason VARCHAR(500) NULL                  AFTER deleted_by;

-- role_permission.created_at is NOT NULL with no default, and Hibernate's
-- @ManyToMany insert writes only (role_id, permission_id). Any runtime grant
-- of a permission to a role therefore fails with error 1364. The seeded
-- matrix works because the migrations supply the value explicitly.
ALTER TABLE role_permission
    MODIFY COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);