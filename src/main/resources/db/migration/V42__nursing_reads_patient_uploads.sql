-- Nurses could not see the files patients attach to a booking.
--
-- A patient may send a photo or PDF of their readings instead of typing them.
-- The nursing queue then shows "Readings not submitted" and refuses "Mark
-- preparation complete" until readings exist, but NURSING never held
-- upload.read, so the nurse could not open the very file that held them. The
-- appointment could only be finished by raising an issue.
--
-- Nursing now reads uploads and transcribes the readings into the record
-- (POST /api/v1/clinical/vitals/appointments/{id}/transcribe, gated on the
-- vitals.verify permission Nursing already holds).

INSERT INTO role_permission (role_id, permission_id, created_at, created_by)
SELECT r.id, p.id, UTC_TIMESTAMP(6), 'system'
FROM roles r JOIN permissions p ON p.code = 'upload.read'
WHERE r.code IN ('NURSING')
  AND NOT EXISTS (SELECT 1 FROM role_permission rp
                  WHERE rp.role_id = r.id AND rp.permission_id = p.id);

-- The patient form accepts 150 characters for "Measured by / location" and the
-- column held 50, so a longer answer failed the whole booking with a database
-- error instead of a message.
ALTER TABLE vitals MODIFY measurement_source VARCHAR(150) NULL;
