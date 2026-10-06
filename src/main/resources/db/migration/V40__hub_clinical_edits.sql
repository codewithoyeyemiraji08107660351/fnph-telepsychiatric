-- =====================================================================
-- V40 - Hub Coordinator edits to reviewer notes and follow-up logistics
--
-- The hub may correct a pharmacist's or laboratory technician's review
-- notes, and the scheduling side of a follow-up (dates, time, mode, status,
-- coordination notes). It never edits what a doctor authored: prescription
-- items, investigation panels, the follow-up recommendation, or a signed
-- clinical note. Those stay with the clinician's own amend/supersede flows.
--
-- Every saved edit writes one row per changed field: the old value, the new
-- value, who, when and why. The application only ever inserts here.
-- =====================================================================

CREATE TABLE clinical_edit_revisions (
                                         id                BIGINT       NOT NULL AUTO_INCREMENT,
                                         public_id         VARCHAR(26)  NULL,
                                         created_at        DATETIME(6)  NOT NULL,
                                         created_by        VARCHAR(100) NULL,

                                         appointment_id    BIGINT       NOT NULL,
                                         target_type       VARCHAR(20)  NOT NULL,
                                         target_id         BIGINT       NOT NULL,
                                         target_public_id  VARCHAR(26)  NOT NULL,
                                         field_name        VARCHAR(40)  NOT NULL,
                                         old_value         TEXT         NULL,
                                         new_value         TEXT         NULL,
                                         reason            VARCHAR(500) NOT NULL,
                                         edited_by         VARCHAR(100) NOT NULL,
                                         edited_by_name    VARCHAR(150) NULL,
    -- One save of one record shares a group, so the history shows it as one edit.
                                         edit_group        VARCHAR(26)  NOT NULL,

                                         PRIMARY KEY (id),
                                         UNIQUE KEY uk_clinical_edit_public_id (public_id),
                                         KEY idx_clinical_edit_appt (appointment_id, created_at),
                                         KEY idx_clinical_edit_target (target_type, target_id, created_at),
                                         CONSTRAINT fk_clinical_edit_appt FOREIGN KEY (appointment_id) REFERENCES appointments (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO permissions (created_at, created_by, code, module, description, is_mutating)
VALUES
    (UTC_TIMESTAMP(6), 'system', 'hub.clinical_edit', 'release',
     'Edit reviewer notes and follow-up logistics, with a reason and kept history', b'1');

INSERT INTO role_permission (role_id, permission_id, created_at, created_by)
SELECT r.id, p.id, UTC_TIMESTAMP(6), 'system'
FROM roles r JOIN permissions p ON p.code = 'hub.clinical_edit'
WHERE r.code IN ('HUB_COORDINATOR')
  AND NOT EXISTS (SELECT 1 FROM role_permission rp
                  WHERE rp.role_id = r.id AND rp.permission_id = p.id);
