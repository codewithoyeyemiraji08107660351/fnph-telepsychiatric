-- =====================================================================
-- V10 - Health Information Management owns the EHR verification source
--
-- The V5 matrix gave ehr_import.upload to the Central Administrator and ICT
-- Support, and ehr_import.activate to the Central Administrator alone. HIM
-- had read only.
--
-- That contradicts the design decision recorded when the verification source
-- was agreed: HIM curates the hospital record and therefore owns the
-- snapshot. The person who maintains patient records and the person who
-- administers the platform should not have to be the same account, and on a
-- small team they otherwise will be.
--
-- Fixed forward rather than by editing V5, which has been applied.
-- =====================================================================

INSERT INTO role_permission (role_id, permission_id, created_at, created_by)
SELECT r.id, p.id, UTC_TIMESTAMP(6), 'system'
FROM roles r
JOIN permissions p ON p.code IN ('ehr_import.upload', 'ehr_import.activate')
WHERE r.code = 'HIM'
  AND NOT EXISTS (
      SELECT 1 FROM role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- ICT Support keeps upload, because a failed import is usually a file or
-- encoding problem and ICT is who HIM calls. It does not get activate:
-- deciding which snapshot the hospital enrols patients against is a records
-- decision, not a technical one.
