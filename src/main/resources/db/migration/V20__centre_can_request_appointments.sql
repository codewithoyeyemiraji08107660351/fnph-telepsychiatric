-- =====================================================================
-- V20 - centres can request consultations
--
-- appointment.request existed and was held by PATIENT alone. A Centre Hub
-- Coordinator could create a referral, record consent and submit it, and then
-- had no permission to ask for a time against it.
--
-- Found by building the endpoint. The referral pathway was half-granted:
-- everything up to the request, and not the request.
-- =====================================================================

INSERT INTO role_permission (role_id, permission_id, created_at, created_by)
SELECT r.id, p.id, UTC_TIMESTAMP(6), 'system'
FROM roles r JOIN permissions p ON p.code = 'appointment.request'
WHERE r.code IN ('CENTRE_HUB_COORDINATOR', 'CENTRE_ASSISTANT_COORDINATOR')
  AND NOT EXISTS (SELECT 1 FROM role_permission rp
                  WHERE rp.role_id = r.id AND rp.permission_id = p.id);

-- The assistant can prepare a referral and request a time. Approval stays with
-- FNPH, so nothing here lets a centre confirm its own booking.
