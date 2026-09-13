-- =====================================================================
-- V19 - two permissions the upload surface needs
--
-- Found by building the endpoints and discovering the matrix had no
-- permission for them. That is the right order round: the surface proves the
-- permission is needed, rather than a permission being seeded because it
-- sounded plausible.
--
-- Everything else the remaining surfaces need already exists:
-- wallet.credit, payment.reconcile, payment.exception_handle,
-- centre_patient.create, centre_patient.read.
-- =====================================================================

INSERT INTO permissions (created_at, created_by, code, module, description, is_mutating)
VALUES
 -- A patient held upload.create and no read at all. They could send a file and
 -- never see it again, which reads as the upload having failed and produces a
 -- support ticket every time.
 (UTC_TIMESTAMP(6), 'system', 'upload.read_own', 'uploads',
  'See files you uploaded yourself', b'0'),

 -- Deliberately narrow. A supporting document the clinician may already have
 -- read is part of the clinical picture, so the uploader removing it after the
 -- fact would change the record behind a consultation that has happened. A
 -- patient who sent the wrong file raises it with the helpdesk.
 (UTC_TIMESTAMP(6), 'system', 'upload.delete', 'uploads',
  'Remove an uploaded file and queue it for deletion from disk', b'1');

INSERT INTO role_permission (role_id, permission_id, created_at, created_by)
SELECT r.id, p.id, UTC_TIMESTAMP(6), 'system'
FROM roles r JOIN permissions p ON p.code = 'upload.read_own'
WHERE r.code IN ('PATIENT', 'CENTRE_HUB_COORDINATOR', 'CENTRE_ASSISTANT_COORDINATOR')
  AND NOT EXISTS (SELECT 1 FROM role_permission rp
                  WHERE rp.role_id = r.id AND rp.permission_id = p.id);

INSERT INTO role_permission (role_id, permission_id, created_at, created_by)
SELECT r.id, p.id, UTC_TIMESTAMP(6), 'system'
FROM roles r JOIN permissions p ON p.code = 'upload.delete'
WHERE r.code IN ('CENTRAL_ADMINISTRATOR', 'ICT_SUPPORT')
  AND NOT EXISTS (SELECT 1 FROM role_permission rp
                  WHERE rp.role_id = r.id AND rp.permission_id = p.id);
