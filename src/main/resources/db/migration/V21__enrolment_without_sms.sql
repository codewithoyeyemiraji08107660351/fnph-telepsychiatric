-- =====================================================================
-- V21 - enrolment code delivery without SMS
--
-- FNPH have confirmed SMS is not required. That removes a dependency and
-- creates a problem, because the enrolment code has to reach the patient
-- somehow and the security property is that it goes to a route already on
-- file rather than one the caller supplies. Otherwise anyone who learned an
-- EHR number and a date of birth could point the account at their own device.
--
-- Two routes replace it, in order of preference:
--
--   1. Email, when the snapshot carries one. Same property as SMS: the
--      destination comes from the hospital record.
--
--   2. Assisted enrolment. The code goes to a staff queue and a coordinator
--      reads it to the patient who is standing in front of them. The
--      destination is a person the hospital can see, which is a stronger
--      check than either channel, and it is how a patient with no email and
--      no smartphone actually enrols.
--
-- The queue is not a fallback for convenience. A patient enrolling remotely
-- with no email on file cannot self-enrol, and that is correct: there is no
-- way to prove the device belongs to them.
-- =====================================================================

ALTER TABLE ehr_verification_records
    ADD COLUMN email_hash   VARCHAR(64) NULL AFTER phone_hash,
    ADD COLUMN email_masked VARCHAR(60) NULL AFTER phone_masked;

ALTER TABLE contact_verifications
    -- Where the code was actually sent, so a support call can be answered.
    ADD COLUMN delivery_route VARCHAR(20) NOT NULL DEFAULT 'EMAIL' AFTER channel,
    -- Set when a member of staff read the code out, and who.
    ADD COLUMN released_to_staff_at DATETIME(6)  NULL AFTER destination_masked,
    ADD COLUMN released_by          VARCHAR(100) NULL AFTER released_to_staff_at;

CREATE INDEX idx_contact_verifications_assisted
    ON contact_verifications (delivery_route, verified_at, expires_at);

-- ---------------------------------------------------------------------
-- Reading a code out to a patient is an identity decision, so it needs its
-- own permission rather than riding on the enrolment queue one.
-- ---------------------------------------------------------------------
INSERT INTO permissions (created_at, created_by, code, module, description, is_mutating)
VALUES (UTC_TIMESTAMP(6), 'system', 'enrolment.release_code', 'ehr',
        'Read an enrolment code to a patient present at a desk', b'1');

INSERT INTO role_permission (role_id, permission_id, created_at, created_by)
SELECT r.id, p.id, UTC_TIMESTAMP(6), 'system'
FROM roles r JOIN permissions p ON p.code = 'enrolment.release_code'
WHERE r.code IN ('HIM', 'HELPDESK', 'HUB_COORDINATOR', 'CENTRAL_ADMINISTRATOR')
  AND NOT EXISTS (SELECT 1 FROM role_permission rp
                  WHERE rp.role_id = r.id AND rp.permission_id = p.id);

INSERT INTO system_configuration
    (created_at, created_by, config_key, config_value, value_type, category,
     description, min_value, max_value, allowed_values, requires_governance,
     is_sensitive, effective_from)
VALUES
 (UTC_TIMESTAMP(6),'system','enrolment_assisted_only','false','BOOLEAN','ehr',
  'When true, every enrolment code goes to the staff queue and none is emailed. '
  'Set this if FNPH decide remote self-enrolment is not acceptable at all.',
  NULL,NULL,'true,false',b'1',b'0',UTC_TIMESTAMP(6));

INSERT INTO configuration_changes
    (created_at, created_by, configuration_id, config_key, previous_value,
     new_value, reason, changed_by, changed_at, effective_from)
SELECT UTC_TIMESTAMP(6), 'system', c.id, c.config_key, NULL, c.config_value,
       'Initial value seeded in V21', 'system', UTC_TIMESTAMP(6), c.effective_from
FROM system_configuration c
WHERE c.config_key = 'enrolment_assisted_only'
  AND NOT EXISTS (SELECT 1 FROM configuration_changes h WHERE h.config_key = c.config_key);
