-- =====================================================================
-- V5 - the permission matrix as data
--
-- WHY THIS IS DATA AND NOT CODE
--   users.role was a single enum column. Three consequences made that
--   unworkable: a permission change needed a redeploy, a user could hold
--   exactly one role, and there was no permission concept at all, so every
--   authorisation decision had to be a role name comparison scattered through
--   the codebase. Moving a single capability between roles then meant finding
--   every comparison that mentioned either role.
--
--   Roles are now bundles. Permissions are the unit of authorisation. Code
--   asks "may this principal approve an appointment", never "is this principal
--   a Hub Coordinator". Reassigning a capability becomes one row.
--
-- TWO ROLES CREATED HERE THAT THE SOURCE DOCUMENTS NEVER DEFINED
--   ICT_SUPPORT appears in the unmatched-EHR verification path alongside the
--   Hub Coordinator and HIM, with no dashboard and no permission set. HELPDESK
--   owns a queue that exists in the administrator prototype, with categories,
--   a ten-minute first-response target and an escalation path, and no role
--   attached to it. Both are created with deliberately narrow scope and
--   neither has any clinical read. Confirm the boundaries with FNPH; changing
--   them is now a data change.
--
-- ON THE CENTRAL ADMINISTRATOR
--   Holds every permission except clinical authorship, professional review and
--   the participant-side actions. Supervising a clinician is not the same as
--   writing in their name, and the specification is explicit that
--   clinician-authored content is not altered by anyone else. Supervised
--   access is its own permission, supervision.view_as, and every use is logged.
--
-- ON ASSOCIATION TABLES
--   role_permission and user_role use composite primary keys and carry no
--   surrogate id and no public_id. An association row has no independent
--   identity; it IS the pair, and the pair is its natural key. This is a
--   documented category, not an ad-hoc exception, and SchemaConventionsTest
--   names it.
-- =====================================================================

CREATE TABLE roles (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    public_id         VARCHAR(26)  NULL,
    created_at        DATETIME(6)  NOT NULL,
    created_by        VARCHAR(100) NULL,
    updated_at        DATETIME(6)  NULL,
    updated_by        VARCHAR(100) NULL,
    code              VARCHAR(50)  NOT NULL,
    name              VARCHAR(100) NOT NULL,
    description       VARCHAR(500) NULL,
    scope             VARCHAR(20)  NOT NULL,
    -- Where authentication lands this role. The specification requires that an
    -- ordinary user is routed straight to their dashboard with no role
    -- selector shown, so the destination has to be a property of the role.
    dashboard_route   VARCHAR(100) NOT NULL,
    is_system         BIT(1)       NOT NULL DEFAULT b'1',
    is_active         BIT(1)       NOT NULL DEFAULT b'1',
    PRIMARY KEY (id),
    UNIQUE KEY uk_roles_code (code),
    UNIQUE KEY uk_roles_public_id (public_id),
    KEY idx_roles_scope (scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE permissions (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    public_id    VARCHAR(26)  NULL,
    created_at   DATETIME(6)  NOT NULL,
    created_by   VARCHAR(100) NULL,
    updated_at   DATETIME(6)  NULL,
    updated_by   VARCHAR(100) NULL,
    code         VARCHAR(80)  NOT NULL,
    module       VARCHAR(40)  NOT NULL,
    description  VARCHAR(500) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_permissions_code (code),
    UNIQUE KEY uk_permissions_public_id (public_id),
    KEY idx_permissions_module (module)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE role_permission (
    role_id        BIGINT       NOT NULL,
    permission_id  BIGINT       NOT NULL,
    created_at     DATETIME(6)  NOT NULL,
    created_by     VARCHAR(100) NULL,
    PRIMARY KEY (role_id, permission_id),
    KEY idx_role_permission_permission (permission_id),
    CONSTRAINT fk_role_permission_role       FOREIGN KEY (role_id)       REFERENCES roles (id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES permissions (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE user_role (
    user_id      BIGINT       NOT NULL,
    role_id      BIGINT       NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    created_by   VARCHAR(100) NULL,
    is_primary   BIT(1)       NOT NULL DEFAULT b'0',
    granted_at   DATETIME(6)  NULL,
    granted_by   VARCHAR(100) NULL,
    grant_reason VARCHAR(500) NULL,
    -- Exactly one primary role per user, enforced by the database rather than
    -- by application discipline. The generated column is the user id when this
    -- row is primary and NULL otherwise, so the unique index permits many
    -- non-primary rows and only one primary. Without this, a user with two
    -- primary roles would make the post-login redirect non-deterministic.
    primary_marker BIGINT GENERATED ALWAYS AS (IF(is_primary, user_id, NULL)) STORED,
    PRIMARY KEY (user_id, role_id),
    UNIQUE KEY uk_user_role_single_primary (primary_marker),
    KEY idx_user_role_role (role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES roles (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Roles
-- ---------------------------------------------------------------------
INSERT INTO roles (created_at, created_by, code, name, scope, description, dashboard_route, is_system, is_active) VALUES
  (UTC_TIMESTAMP(6),'system','CENTRAL_ADMINISTRATOR','Central Administrator','FNPH','System-wide administration, supervision and configuration','/admin',b'1',b'1'),
  (UTC_TIMESTAMP(6),'system','HUB_COORDINATOR','Hub Coordinator','FNPH','Booking approval, assignment, session oversight and bundle release','/hub',b'1',b'1'),
  (UTC_TIMESTAMP(6),'system','DOCTOR','Doctor','FNPH','Pre-review, consultation and clinical authorship','/clinical',b'1',b'1'),
  (UTC_TIMESTAMP(6),'system','PHARMACIST','Pharmacist','FNPH','Prescription transcription and professional verification','/reviews/pharmacy',b'1',b'1'),
  (UTC_TIMESTAMP(6),'system','LABORATORY_TECHNICIAN','Laboratory Technician','FNPH','Investigation transcription and review','/reviews/laboratory',b'1',b'1'),
  (UTC_TIMESTAMP(6),'system','NURSING','Nurse','FNPH','Vitals entry into the offline EHR and room preparation','/queues/nursing',b'1',b'1'),
  (UTC_TIMESTAMP(6),'system','HIM','Health Information Management','FNPH','Offline record retrieval and preparation','/queues/him',b'1',b'1'),
  (UTC_TIMESTAMP(6),'system','FINANCE','Finance Officer','FNPH','Payment monitoring, reconciliation and centre wallet funding','/finance',b'1',b'1'),
  (UTC_TIMESTAMP(6),'system','ICT_SUPPORT','ICT Support','FNPH','Technical support and verification exception handling. No clinical access.','/ict',b'1',b'1'),
  (UTC_TIMESTAMP(6),'system','HELPDESK','Helpdesk Agent','FNPH','Support ticket handling and escalation. No clinical or financial access.','/helpdesk',b'1',b'1'),
  (UTC_TIMESTAMP(6),'system','PATIENT','Patient','PATIENT','Verified existing FNPH Kaduna patient','/portal',b'1',b'1'),
  (UTC_TIMESTAMP(6),'system','CENTRE_HUB_COORDINATOR','Centre Hub Coordinator','CENTRE','Centre patients, referrals, consultations and released bundles','/centre',b'1',b'1'),
  (UTC_TIMESTAMP(6),'system','CENTRE_ASSISTANT_COORDINATOR','Assistant Centre Coordinator','CENTRE','Centre workspace with reduced privilege','/centre',b'1',b'1'),
  (UTC_TIMESTAMP(6),'system','CENTRE_PHARMACY','Centre Pharmacist','CENTRE','Optional local prescription handling, activated per centre','/centre/pharmacy',b'1',b'1'),
  (UTC_TIMESTAMP(6),'system','CENTRE_LABORATORY','Centre Laboratory','CENTRE','Optional local investigation handling, activated per centre','/centre/laboratory',b'1',b'1'),
  (UTC_TIMESTAMP(6),'system','CENTRE_HIM','Centre HIM','CENTRE','Optional local record handling, activated per centre','/centre/him',b'1',b'1');

-- ---------------------------------------------------------------------
-- Permission catalogue
-- ---------------------------------------------------------------------
INSERT INTO permissions (created_at, created_by, code, module, description) VALUES
  (UTC_TIMESTAMP(6),'system','user.read','identity','View staff and centre user accounts'),
  (UTC_TIMESTAMP(6),'system','user.create','identity','Create a user account'),
  (UTC_TIMESTAMP(6),'system','user.update','identity','Amend a user account'),
  (UTC_TIMESTAMP(6),'system','user.deactivate','identity','Deactivate a user without destroying their history'),
  (UTC_TIMESTAMP(6),'system','user.reset_password','identity','Force a password reset on another account'),
  (UTC_TIMESTAMP(6),'system','role.read','identity','View the role and permission matrix'),
  (UTC_TIMESTAMP(6),'system','role.assign','identity','Assign or remove roles on a user account'),
  (UTC_TIMESTAMP(6),'system','session.revoke','identity','Revoke another user''s session or device'),
  (UTC_TIMESTAMP(6),'system','mfa.reset','identity','Reset a user''s second factor'),
  (UTC_TIMESTAMP(6),'system','profile.read_own','identity','View own profile'),
  (UTC_TIMESTAMP(6),'system','profile.update_own','identity','Amend own profile'),
  (UTC_TIMESTAMP(6),'system','supervision.view_as','supervision','Open another user''s dashboard as a supervised, logged session'),
  (UTC_TIMESTAMP(6),'system','supervision.read_log','supervision','Read the supervised access log'),
  (UTC_TIMESTAMP(6),'system','patient.read','patient','View FNPH patient records'),
  (UTC_TIMESTAMP(6),'system','patient.read_own','patient','View own patient record'),
  (UTC_TIMESTAMP(6),'system','patient.create','patient','Create an FNPH patient record'),
  (UTC_TIMESTAMP(6),'system','patient.update','patient','Amend an FNPH patient record'),
  (UTC_TIMESTAMP(6),'system','patient.verify','patient','Decide an unmatched EHR verification request'),
  (UTC_TIMESTAMP(6),'system','patient.activate','patient','Activate a verified patient account'),
  (UTC_TIMESTAMP(6),'system','patient.flag_drift','patient','Review a record whose imported details changed after activation'),
  (UTC_TIMESTAMP(6),'system','ehr_import.upload','ehr','Upload an EHR verification export'),
  (UTC_TIMESTAMP(6),'system','ehr_import.activate','ehr','Make an uploaded export the active verification source'),
  (UTC_TIMESTAMP(6),'system','ehr_import.read','ehr','View import history and validation reports'),
  (UTC_TIMESTAMP(6),'system','ehr_verification.resolve','ehr','Work the unmatched verification queue'),
  (UTC_TIMESTAMP(6),'system','triage.submit','triage','Submit triage answers'),
  (UTC_TIMESTAMP(6),'system','triage.read','triage','View submitted triage answers'),
  (UTC_TIMESTAMP(6),'system','consent.accept','triage','Accept a consent document version'),
  (UTC_TIMESTAMP(6),'system','consent.read','triage','View recorded consent evidence'),
  (UTC_TIMESTAMP(6),'system','consent.manage_versions','triage','Publish and retire consent document versions'),
  (UTC_TIMESTAMP(6),'system','vitals.submit','vitals','Submit vitals'),
  (UTC_TIMESTAMP(6),'system','vitals.read','vitals','View submitted vitals'),
  (UTC_TIMESTAMP(6),'system','vitals.verify','vitals','Record submitted vitals in the offline EHR and mark them verified'),
  (UTC_TIMESTAMP(6),'system','upload.create','upload','Upload a supporting document or result'),
  (UTC_TIMESTAMP(6),'system','upload.read','upload','View uploaded documents'),
  (UTC_TIMESTAMP(6),'system','upload.quarantine','upload','Quarantine or release a scanned upload'),
  (UTC_TIMESTAMP(6),'system','schedule.publish','schedule','Publish consultation dates and generate slots'),
  (UTC_TIMESTAMP(6),'system','schedule.read','schedule','View published schedules'),
  (UTC_TIMESTAMP(6),'system','slot.hold','schedule','Place a temporary hold on a slot'),
  (UTC_TIMESTAMP(6),'system','slot.read','schedule','View slot availability'),
  (UTC_TIMESTAMP(6),'system','appointment.request','schedule','Request an appointment'),
  (UTC_TIMESTAMP(6),'system','appointment.read','schedule','View appointments across the service'),
  (UTC_TIMESTAMP(6),'system','appointment.read_own','schedule','View own appointments'),
  (UTC_TIMESTAMP(6),'system','appointment.approve','schedule','Approve a requested appointment'),
  (UTC_TIMESTAMP(6),'system','appointment.reject','schedule','Reject a requested appointment'),
  (UTC_TIMESTAMP(6),'system','appointment.assign_doctor','schedule','Assign the consulting doctor'),
  (UTC_TIMESTAMP(6),'system','appointment.assign_team','schedule','Assign pharmacy, laboratory, nursing and HIM'),
  (UTC_TIMESTAMP(6),'system','appointment.assign_room','schedule','Assign the consultation room'),
  (UTC_TIMESTAMP(6),'system','appointment.cancel','schedule','Cancel an appointment'),
  (UTC_TIMESTAMP(6),'system','appointment.reschedule','schedule','Reschedule an appointment'),
  (UTC_TIMESTAMP(6),'system','appointment.mark_no_show','schedule','Record a no-show'),
  (UTC_TIMESTAMP(6),'system','room.read','room','View rooms'),
  (UTC_TIMESTAMP(6),'system','room.manage','room','Create, amend and deactivate rooms'),
  (UTC_TIMESTAMP(6),'system','doctor_availability.read','room','View doctor availability'),
  (UTC_TIMESTAMP(6),'system','doctor_availability.manage','room','Set doctor availability'),
  (UTC_TIMESTAMP(6),'system','consultation.join_as_doctor','consultation','Join a consultation as the clinician'),
  (UTC_TIMESTAMP(6),'system','consultation.join_as_patient','consultation','Join own consultation as the patient'),
  (UTC_TIMESTAMP(6),'system','consultation.join_as_centre','consultation','Join a consultation as the referring centre'),
  (UTC_TIMESTAMP(6),'system','consultation.read','consultation','View consultation records and attendance'),
  (UTC_TIMESTAMP(6),'system','consultation.terminate','consultation','End a session early and record the reason and safety action'),
  (UTC_TIMESTAMP(6),'system','consultation.switch_modality','consultation','Fall back from video to audio'),
  (UTC_TIMESTAMP(6),'system','recording.start','consultation','Start a recording where governance has approved it'),
  (UTC_TIMESTAMP(6),'system','recording.read','consultation','View a recording or transcript'),
  (UTC_TIMESTAMP(6),'system','clinical_note.write','clinical','Author a clinical note'),
  (UTC_TIMESTAMP(6),'system','clinical_note.read','clinical','Read a clinical note'),
  (UTC_TIMESTAMP(6),'system','clinical_note.sign','clinical','Sign a clinical note'),
  (UTC_TIMESTAMP(6),'system','prescription.write','clinical','Author or supersede a prescription'),
  (UTC_TIMESTAMP(6),'system','prescription.read','clinical','Read a prescription'),
  (UTC_TIMESTAMP(6),'system','prescription.read_own','clinical','Read own prescriptions'),
  (UTC_TIMESTAMP(6),'system','investigation.write','clinical','Author an investigation request'),
  (UTC_TIMESTAMP(6),'system','investigation.read','clinical','Read an investigation request'),
  (UTC_TIMESTAMP(6),'system','investigation.read_own','clinical','Read own investigation requests'),
  (UTC_TIMESTAMP(6),'system','follow_up.write','clinical','Record a follow-up recommendation'),
  (UTC_TIMESTAMP(6),'system','follow_up.read','clinical','Read follow-up recommendations'),
  (UTC_TIMESTAMP(6),'system','follow_up.read_own','clinical','Read own follow-up recommendations'),
  (UTC_TIMESTAMP(6),'system','review.pharmacy','review','Transcribe and professionally verify a prescription'),
  (UTC_TIMESTAMP(6),'system','review.laboratory','review','Transcribe and review an investigation request'),
  (UTC_TIMESTAMP(6),'system','review.submit_to_hub','review','Submit a completed review forward to the Hub Coordinator'),
  (UTC_TIMESTAMP(6),'system','review.read','review','View review status and history'),
  (UTC_TIMESTAMP(6),'system','release_bundle.read','release','View bundle completeness'),
  (UTC_TIMESTAMP(6),'system','release_bundle.release','release','Release a complete clinical bundle'),
  (UTC_TIMESTAMP(6),'system','queue.nursing','queue','Work the nursing preparation queue'),
  (UTC_TIMESTAMP(6),'system','queue.him','queue','Work the health information management queue'),
  (UTC_TIMESTAMP(6),'system','document.issue','document','Issue a verifiable clinical document'),
  (UTC_TIMESTAMP(6),'system','document.read','document','View issued documents'),
  (UTC_TIMESTAMP(6),'system','document.read_own','document','View own issued documents'),
  (UTC_TIMESTAMP(6),'system','document.download','document','Download an issued document within its limit'),
  (UTC_TIMESTAMP(6),'system','document.revoke','document','Revoke an issued document'),
  (UTC_TIMESTAMP(6),'system','payment.initiate','finance','Start a payment'),
  (UTC_TIMESTAMP(6),'system','payment.read','finance','View payments across the service'),
  (UTC_TIMESTAMP(6),'system','payment.read_own','finance','View own payments'),
  (UTC_TIMESTAMP(6),'system','payment.reconcile','finance','Run and review reconciliation'),
  (UTC_TIMESTAMP(6),'system','payment.exception_handle','finance','Work failed, pending, reversed and unmatched transactions'),
  (UTC_TIMESTAMP(6),'system','payment.refund','finance','Issue a refund'),
  (UTC_TIMESTAMP(6),'system','wallet.read_balance','finance','View centre wallet balances and thresholds'),
  (UTC_TIMESTAMP(6),'system','wallet.credit','finance','Credit a centre wallet from programme funding'),
  (UTC_TIMESTAMP(6),'system','wallet.read_ledger','finance','Read the centre wallet ledger'),
  (UTC_TIMESTAMP(6),'system','finance_report.read','finance','Generate financial and reconciliation reports'),
  (UTC_TIMESTAMP(6),'system','centre.read','centre','View all centres'),
  (UTC_TIMESTAMP(6),'system','centre.read_own','centre','View own centre'),
  (UTC_TIMESTAMP(6),'system','centre.create','centre','Create a centre'),
  (UTC_TIMESTAMP(6),'system','centre.update','centre','Amend a centre'),
  (UTC_TIMESTAMP(6),'system','centre.activate','centre','Activate or suspend a centre'),
  (UTC_TIMESTAMP(6),'system','centre_capability.manage','centre','Activate optional local pharmacy, laboratory or HIM roles at a centre'),
  (UTC_TIMESTAMP(6),'system','centre_patient.read','centre','View centre patient records'),
  (UTC_TIMESTAMP(6),'system','centre_patient.create','centre','Create a centre patient record'),
  (UTC_TIMESTAMP(6),'system','centre_patient.update','centre','Amend a centre patient record'),
  (UTC_TIMESTAMP(6),'system','centre_referral.create','centre','Submit a referral and appointment request'),
  (UTC_TIMESTAMP(6),'system','centre_referral.read','centre','View submitted referrals'),
  (UTC_TIMESTAMP(6),'system','centre_bundle.read','centre','View released care bundles'),
  (UTC_TIMESTAMP(6),'system','centre_bundle.mark_treated','centre','Mark a released bundle as treated'),
  (UTC_TIMESTAMP(6),'system','centre_report.read','centre','View centre consultation and utilisation counts'),
  (UTC_TIMESTAMP(6),'system','notification.read_own','notification','Read own notifications'),
  (UTC_TIMESTAMP(6),'system','notification.send','notification','Send an operational notification'),
  (UTC_TIMESTAMP(6),'system','notification.manage_templates','notification','Manage notification templates'),
  (UTC_TIMESTAMP(6),'system','ticket.create','support','Raise a support ticket'),
  (UTC_TIMESTAMP(6),'system','ticket.read','support','View all support tickets'),
  (UTC_TIMESTAMP(6),'system','ticket.read_own','support','View own support tickets'),
  (UTC_TIMESTAMP(6),'system','ticket.assign','support','Assign a ticket to an agent'),
  (UTC_TIMESTAMP(6),'system','ticket.respond','support','Respond to a ticket'),
  (UTC_TIMESTAMP(6),'system','ticket.escalate','support','Escalate a ticket'),
  (UTC_TIMESTAMP(6),'system','ticket.close','support','Close a ticket'),
  (UTC_TIMESTAMP(6),'system','audit.read','audit','Read the audit trail'),
  (UTC_TIMESTAMP(6),'system','audit.export','audit','Export an audit report'),
  (UTC_TIMESTAMP(6),'system','config.read','config','View system configuration'),
  (UTC_TIMESTAMP(6),'system','config.update','config','Change system configuration with a recorded reason'),
  (UTC_TIMESTAMP(6),'system','system.health_read','system','View service health and integration status');

-- ---------------------------------------------------------------------
-- The matrix. Joined by code so a typo fails loudly at migration time
-- rather than silently granting nothing.
-- ---------------------------------------------------------------------
INSERT INTO role_permission (role_id, permission_id, created_at, created_by)
SELECT r.id, p.id, UTC_TIMESTAMP(6), 'system'
FROM (
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'user.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'user.create' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'user.update' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'user.deactivate' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'user.reset_password' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'role.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'role.assign' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'session.revoke' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'mfa.reset' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'profile.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'profile.update_own' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'supervision.view_as' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'supervision.read_log' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'patient.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'patient.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'patient.create' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'patient.update' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'patient.verify' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'patient.activate' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'patient.flag_drift' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'ehr_import.upload' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'ehr_import.activate' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'ehr_import.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'ehr_verification.resolve' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'triage.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'consent.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'consent.manage_versions' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'vitals.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'vitals.verify' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'upload.create' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'upload.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'upload.quarantine' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'schedule.publish' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'schedule.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'slot.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'appointment.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'appointment.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'appointment.approve' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'appointment.reject' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'appointment.assign_doctor' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'appointment.assign_team' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'appointment.assign_room' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'appointment.cancel' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'appointment.reschedule' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'appointment.mark_no_show' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'room.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'room.manage' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'doctor_availability.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'doctor_availability.manage' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'consultation.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'consultation.terminate' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'consultation.switch_modality' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'recording.start' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'recording.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'clinical_note.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'prescription.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'prescription.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'investigation.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'investigation.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'follow_up.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'follow_up.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'review.submit_to_hub' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'review.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'release_bundle.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'release_bundle.release' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'queue.nursing' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'queue.him' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'document.issue' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'document.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'document.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'document.download' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'document.revoke' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'payment.initiate' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'payment.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'payment.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'payment.reconcile' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'payment.exception_handle' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'payment.refund' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'wallet.read_balance' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'wallet.credit' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'wallet.read_ledger' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'finance_report.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'centre.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'centre.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'centre.create' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'centre.update' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'centre.activate' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'centre_capability.manage' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'centre_patient.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'centre_patient.create' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'centre_patient.update' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'centre_referral.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'centre_bundle.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'centre_bundle.mark_treated' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'centre_report.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'notification.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'notification.send' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'notification.manage_templates' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'ticket.create' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'ticket.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'ticket.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'ticket.assign' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'ticket.respond' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'ticket.escalate' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'ticket.close' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'audit.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'audit.export' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'config.read' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'config.update' AS permission_code
  UNION ALL
  SELECT 'CENTRAL_ADMINISTRATOR' AS role_code, 'system.health_read' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'appointment.approve' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'appointment.assign_doctor' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'appointment.assign_room' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'appointment.assign_team' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'appointment.cancel' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'appointment.mark_no_show' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'appointment.read' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'appointment.reject' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'appointment.reschedule' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'centre.read' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'centre_patient.read' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'centre_referral.read' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'clinical_note.read' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'consent.read' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'consultation.read' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'doctor_availability.manage' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'doctor_availability.read' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'document.read' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'ehr_verification.resolve' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'follow_up.read' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'investigation.read' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'notification.read_own' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'notification.send' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'patient.read' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'patient.verify' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'prescription.read' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'profile.read_own' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'profile.update_own' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'release_bundle.read' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'release_bundle.release' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'review.read' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'room.read' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'schedule.publish' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'schedule.read' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'slot.read' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'ticket.create' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'ticket.read_own' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'triage.read' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'upload.read' AS permission_code
  UNION ALL
  SELECT 'HUB_COORDINATOR' AS role_code, 'vitals.read' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'appointment.read' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'centre_patient.read' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'centre_referral.read' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'clinical_note.read' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'clinical_note.sign' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'clinical_note.write' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'consent.read' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'consultation.join_as_doctor' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'consultation.read' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'consultation.switch_modality' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'consultation.terminate' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'doctor_availability.read' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'document.read' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'follow_up.read' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'follow_up.write' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'investigation.read' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'investigation.write' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'notification.read_own' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'patient.read' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'prescription.read' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'prescription.write' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'profile.read_own' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'profile.update_own' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'recording.start' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'review.read' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'schedule.read' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'ticket.create' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'ticket.read_own' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'triage.read' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'upload.read' AS permission_code
  UNION ALL
  SELECT 'DOCTOR' AS role_code, 'vitals.read' AS permission_code
  UNION ALL
  SELECT 'PHARMACIST' AS role_code, 'appointment.read' AS permission_code
  UNION ALL
  SELECT 'PHARMACIST' AS role_code, 'centre_patient.read' AS permission_code
  UNION ALL
  SELECT 'PHARMACIST' AS role_code, 'document.read' AS permission_code
  UNION ALL
  SELECT 'PHARMACIST' AS role_code, 'notification.read_own' AS permission_code
  UNION ALL
  SELECT 'PHARMACIST' AS role_code, 'patient.read' AS permission_code
  UNION ALL
  SELECT 'PHARMACIST' AS role_code, 'prescription.read' AS permission_code
  UNION ALL
  SELECT 'PHARMACIST' AS role_code, 'profile.read_own' AS permission_code
  UNION ALL
  SELECT 'PHARMACIST' AS role_code, 'profile.update_own' AS permission_code
  UNION ALL
  SELECT 'PHARMACIST' AS role_code, 'review.pharmacy' AS permission_code
  UNION ALL
  SELECT 'PHARMACIST' AS role_code, 'review.read' AS permission_code
  UNION ALL
  SELECT 'PHARMACIST' AS role_code, 'review.submit_to_hub' AS permission_code
  UNION ALL
  SELECT 'PHARMACIST' AS role_code, 'ticket.create' AS permission_code
  UNION ALL
  SELECT 'PHARMACIST' AS role_code, 'ticket.read_own' AS permission_code
  UNION ALL
  SELECT 'PHARMACIST' AS role_code, 'upload.read' AS permission_code
  UNION ALL
  SELECT 'PHARMACIST' AS role_code, 'vitals.read' AS permission_code
  UNION ALL
  SELECT 'LABORATORY_TECHNICIAN' AS role_code, 'appointment.read' AS permission_code
  UNION ALL
  SELECT 'LABORATORY_TECHNICIAN' AS role_code, 'centre_patient.read' AS permission_code
  UNION ALL
  SELECT 'LABORATORY_TECHNICIAN' AS role_code, 'document.read' AS permission_code
  UNION ALL
  SELECT 'LABORATORY_TECHNICIAN' AS role_code, 'investigation.read' AS permission_code
  UNION ALL
  SELECT 'LABORATORY_TECHNICIAN' AS role_code, 'notification.read_own' AS permission_code
  UNION ALL
  SELECT 'LABORATORY_TECHNICIAN' AS role_code, 'patient.read' AS permission_code
  UNION ALL
  SELECT 'LABORATORY_TECHNICIAN' AS role_code, 'profile.read_own' AS permission_code
  UNION ALL
  SELECT 'LABORATORY_TECHNICIAN' AS role_code, 'profile.update_own' AS permission_code
  UNION ALL
  SELECT 'LABORATORY_TECHNICIAN' AS role_code, 'review.laboratory' AS permission_code
  UNION ALL
  SELECT 'LABORATORY_TECHNICIAN' AS role_code, 'review.read' AS permission_code
  UNION ALL
  SELECT 'LABORATORY_TECHNICIAN' AS role_code, 'review.submit_to_hub' AS permission_code
  UNION ALL
  SELECT 'LABORATORY_TECHNICIAN' AS role_code, 'ticket.create' AS permission_code
  UNION ALL
  SELECT 'LABORATORY_TECHNICIAN' AS role_code, 'ticket.read_own' AS permission_code
  UNION ALL
  SELECT 'LABORATORY_TECHNICIAN' AS role_code, 'upload.read' AS permission_code
  UNION ALL
  SELECT 'LABORATORY_TECHNICIAN' AS role_code, 'vitals.read' AS permission_code
  UNION ALL
  SELECT 'NURSING' AS role_code, 'appointment.assign_room' AS permission_code
  UNION ALL
  SELECT 'NURSING' AS role_code, 'appointment.read' AS permission_code
  UNION ALL
  SELECT 'NURSING' AS role_code, 'notification.read_own' AS permission_code
  UNION ALL
  SELECT 'NURSING' AS role_code, 'patient.read' AS permission_code
  UNION ALL
  SELECT 'NURSING' AS role_code, 'profile.read_own' AS permission_code
  UNION ALL
  SELECT 'NURSING' AS role_code, 'profile.update_own' AS permission_code
  UNION ALL
  SELECT 'NURSING' AS role_code, 'queue.nursing' AS permission_code
  UNION ALL
  SELECT 'NURSING' AS role_code, 'room.read' AS permission_code
  UNION ALL
  SELECT 'NURSING' AS role_code, 'ticket.create' AS permission_code
  UNION ALL
  SELECT 'NURSING' AS role_code, 'ticket.read_own' AS permission_code
  UNION ALL
  SELECT 'NURSING' AS role_code, 'vitals.read' AS permission_code
  UNION ALL
  SELECT 'NURSING' AS role_code, 'vitals.verify' AS permission_code
  UNION ALL
  SELECT 'HIM' AS role_code, 'appointment.read' AS permission_code
  UNION ALL
  SELECT 'HIM' AS role_code, 'document.read' AS permission_code
  UNION ALL
  SELECT 'HIM' AS role_code, 'ehr_import.read' AS permission_code
  UNION ALL
  SELECT 'HIM' AS role_code, 'ehr_verification.resolve' AS permission_code
  UNION ALL
  SELECT 'HIM' AS role_code, 'notification.read_own' AS permission_code
  UNION ALL
  SELECT 'HIM' AS role_code, 'patient.flag_drift' AS permission_code
  UNION ALL
  SELECT 'HIM' AS role_code, 'patient.read' AS permission_code
  UNION ALL
  SELECT 'HIM' AS role_code, 'patient.verify' AS permission_code
  UNION ALL
  SELECT 'HIM' AS role_code, 'profile.read_own' AS permission_code
  UNION ALL
  SELECT 'HIM' AS role_code, 'profile.update_own' AS permission_code
  UNION ALL
  SELECT 'HIM' AS role_code, 'queue.him' AS permission_code
  UNION ALL
  SELECT 'HIM' AS role_code, 'ticket.create' AS permission_code
  UNION ALL
  SELECT 'HIM' AS role_code, 'ticket.read_own' AS permission_code
  UNION ALL
  SELECT 'FINANCE' AS role_code, 'appointment.read' AS permission_code
  UNION ALL
  SELECT 'FINANCE' AS role_code, 'centre.read' AS permission_code
  UNION ALL
  SELECT 'FINANCE' AS role_code, 'finance_report.read' AS permission_code
  UNION ALL
  SELECT 'FINANCE' AS role_code, 'notification.read_own' AS permission_code
  UNION ALL
  SELECT 'FINANCE' AS role_code, 'patient.read' AS permission_code
  UNION ALL
  SELECT 'FINANCE' AS role_code, 'payment.exception_handle' AS permission_code
  UNION ALL
  SELECT 'FINANCE' AS role_code, 'payment.read' AS permission_code
  UNION ALL
  SELECT 'FINANCE' AS role_code, 'payment.reconcile' AS permission_code
  UNION ALL
  SELECT 'FINANCE' AS role_code, 'payment.refund' AS permission_code
  UNION ALL
  SELECT 'FINANCE' AS role_code, 'profile.read_own' AS permission_code
  UNION ALL
  SELECT 'FINANCE' AS role_code, 'profile.update_own' AS permission_code
  UNION ALL
  SELECT 'FINANCE' AS role_code, 'ticket.create' AS permission_code
  UNION ALL
  SELECT 'FINANCE' AS role_code, 'ticket.read_own' AS permission_code
  UNION ALL
  SELECT 'FINANCE' AS role_code, 'wallet.credit' AS permission_code
  UNION ALL
  SELECT 'FINANCE' AS role_code, 'wallet.read_balance' AS permission_code
  UNION ALL
  SELECT 'FINANCE' AS role_code, 'wallet.read_ledger' AS permission_code
  UNION ALL
  SELECT 'ICT_SUPPORT' AS role_code, 'config.read' AS permission_code
  UNION ALL
  SELECT 'ICT_SUPPORT' AS role_code, 'ehr_import.read' AS permission_code
  UNION ALL
  SELECT 'ICT_SUPPORT' AS role_code, 'ehr_import.upload' AS permission_code
  UNION ALL
  SELECT 'ICT_SUPPORT' AS role_code, 'ehr_verification.resolve' AS permission_code
  UNION ALL
  SELECT 'ICT_SUPPORT' AS role_code, 'mfa.reset' AS permission_code
  UNION ALL
  SELECT 'ICT_SUPPORT' AS role_code, 'notification.read_own' AS permission_code
  UNION ALL
  SELECT 'ICT_SUPPORT' AS role_code, 'notification.send' AS permission_code
  UNION ALL
  SELECT 'ICT_SUPPORT' AS role_code, 'profile.read_own' AS permission_code
  UNION ALL
  SELECT 'ICT_SUPPORT' AS role_code, 'profile.update_own' AS permission_code
  UNION ALL
  SELECT 'ICT_SUPPORT' AS role_code, 'session.revoke' AS permission_code
  UNION ALL
  SELECT 'ICT_SUPPORT' AS role_code, 'system.health_read' AS permission_code
  UNION ALL
  SELECT 'ICT_SUPPORT' AS role_code, 'ticket.create' AS permission_code
  UNION ALL
  SELECT 'ICT_SUPPORT' AS role_code, 'ticket.escalate' AS permission_code
  UNION ALL
  SELECT 'ICT_SUPPORT' AS role_code, 'ticket.read' AS permission_code
  UNION ALL
  SELECT 'ICT_SUPPORT' AS role_code, 'ticket.read_own' AS permission_code
  UNION ALL
  SELECT 'ICT_SUPPORT' AS role_code, 'ticket.respond' AS permission_code
  UNION ALL
  SELECT 'ICT_SUPPORT' AS role_code, 'upload.quarantine' AS permission_code
  UNION ALL
  SELECT 'ICT_SUPPORT' AS role_code, 'user.read' AS permission_code
  UNION ALL
  SELECT 'ICT_SUPPORT' AS role_code, 'user.reset_password' AS permission_code
  UNION ALL
  SELECT 'HELPDESK' AS role_code, 'appointment.read' AS permission_code
  UNION ALL
  SELECT 'HELPDESK' AS role_code, 'notification.read_own' AS permission_code
  UNION ALL
  SELECT 'HELPDESK' AS role_code, 'payment.read' AS permission_code
  UNION ALL
  SELECT 'HELPDESK' AS role_code, 'profile.read_own' AS permission_code
  UNION ALL
  SELECT 'HELPDESK' AS role_code, 'profile.update_own' AS permission_code
  UNION ALL
  SELECT 'HELPDESK' AS role_code, 'system.health_read' AS permission_code
  UNION ALL
  SELECT 'HELPDESK' AS role_code, 'ticket.assign' AS permission_code
  UNION ALL
  SELECT 'HELPDESK' AS role_code, 'ticket.close' AS permission_code
  UNION ALL
  SELECT 'HELPDESK' AS role_code, 'ticket.create' AS permission_code
  UNION ALL
  SELECT 'HELPDESK' AS role_code, 'ticket.escalate' AS permission_code
  UNION ALL
  SELECT 'HELPDESK' AS role_code, 'ticket.read' AS permission_code
  UNION ALL
  SELECT 'HELPDESK' AS role_code, 'ticket.read_own' AS permission_code
  UNION ALL
  SELECT 'HELPDESK' AS role_code, 'ticket.respond' AS permission_code
  UNION ALL
  SELECT 'HELPDESK' AS role_code, 'user.read' AS permission_code
  UNION ALL
  SELECT 'PATIENT' AS role_code, 'appointment.cancel' AS permission_code
  UNION ALL
  SELECT 'PATIENT' AS role_code, 'appointment.read_own' AS permission_code
  UNION ALL
  SELECT 'PATIENT' AS role_code, 'appointment.request' AS permission_code
  UNION ALL
  SELECT 'PATIENT' AS role_code, 'appointment.reschedule' AS permission_code
  UNION ALL
  SELECT 'PATIENT' AS role_code, 'consent.accept' AS permission_code
  UNION ALL
  SELECT 'PATIENT' AS role_code, 'consultation.join_as_patient' AS permission_code
  UNION ALL
  SELECT 'PATIENT' AS role_code, 'document.download' AS permission_code
  UNION ALL
  SELECT 'PATIENT' AS role_code, 'document.read_own' AS permission_code
  UNION ALL
  SELECT 'PATIENT' AS role_code, 'follow_up.read_own' AS permission_code
  UNION ALL
  SELECT 'PATIENT' AS role_code, 'investigation.read_own' AS permission_code
  UNION ALL
  SELECT 'PATIENT' AS role_code, 'notification.read_own' AS permission_code
  UNION ALL
  SELECT 'PATIENT' AS role_code, 'patient.read_own' AS permission_code
  UNION ALL
  SELECT 'PATIENT' AS role_code, 'payment.initiate' AS permission_code
  UNION ALL
  SELECT 'PATIENT' AS role_code, 'payment.read_own' AS permission_code
  UNION ALL
  SELECT 'PATIENT' AS role_code, 'prescription.read_own' AS permission_code
  UNION ALL
  SELECT 'PATIENT' AS role_code, 'profile.read_own' AS permission_code
  UNION ALL
  SELECT 'PATIENT' AS role_code, 'profile.update_own' AS permission_code
  UNION ALL
  SELECT 'PATIENT' AS role_code, 'schedule.read' AS permission_code
  UNION ALL
  SELECT 'PATIENT' AS role_code, 'slot.hold' AS permission_code
  UNION ALL
  SELECT 'PATIENT' AS role_code, 'slot.read' AS permission_code
  UNION ALL
  SELECT 'PATIENT' AS role_code, 'ticket.create' AS permission_code
  UNION ALL
  SELECT 'PATIENT' AS role_code, 'ticket.read_own' AS permission_code
  UNION ALL
  SELECT 'PATIENT' AS role_code, 'triage.submit' AS permission_code
  UNION ALL
  SELECT 'PATIENT' AS role_code, 'upload.create' AS permission_code
  UNION ALL
  SELECT 'PATIENT' AS role_code, 'vitals.submit' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'appointment.cancel' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'appointment.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'appointment.reschedule' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'centre.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'centre_bundle.mark_treated' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'centre_bundle.read' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'centre_patient.create' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'centre_patient.read' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'centre_patient.update' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'centre_referral.create' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'centre_referral.read' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'centre_report.read' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'consent.accept' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'consultation.join_as_centre' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'consultation.read' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'document.download' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'document.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'notification.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'profile.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'profile.update_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'schedule.read' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'slot.hold' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'slot.read' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'ticket.create' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'ticket.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'upload.create' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'upload.read' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HUB_COORDINATOR' AS role_code, 'vitals.submit' AS permission_code
  UNION ALL
  SELECT 'CENTRE_ASSISTANT_COORDINATOR' AS role_code, 'appointment.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_ASSISTANT_COORDINATOR' AS role_code, 'centre.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_ASSISTANT_COORDINATOR' AS role_code, 'centre_bundle.read' AS permission_code
  UNION ALL
  SELECT 'CENTRE_ASSISTANT_COORDINATOR' AS role_code, 'centre_patient.create' AS permission_code
  UNION ALL
  SELECT 'CENTRE_ASSISTANT_COORDINATOR' AS role_code, 'centre_patient.read' AS permission_code
  UNION ALL
  SELECT 'CENTRE_ASSISTANT_COORDINATOR' AS role_code, 'centre_referral.create' AS permission_code
  UNION ALL
  SELECT 'CENTRE_ASSISTANT_COORDINATOR' AS role_code, 'centre_referral.read' AS permission_code
  UNION ALL
  SELECT 'CENTRE_ASSISTANT_COORDINATOR' AS role_code, 'centre_report.read' AS permission_code
  UNION ALL
  SELECT 'CENTRE_ASSISTANT_COORDINATOR' AS role_code, 'consultation.join_as_centre' AS permission_code
  UNION ALL
  SELECT 'CENTRE_ASSISTANT_COORDINATOR' AS role_code, 'document.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_ASSISTANT_COORDINATOR' AS role_code, 'notification.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_ASSISTANT_COORDINATOR' AS role_code, 'profile.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_ASSISTANT_COORDINATOR' AS role_code, 'profile.update_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_ASSISTANT_COORDINATOR' AS role_code, 'schedule.read' AS permission_code
  UNION ALL
  SELECT 'CENTRE_ASSISTANT_COORDINATOR' AS role_code, 'slot.read' AS permission_code
  UNION ALL
  SELECT 'CENTRE_ASSISTANT_COORDINATOR' AS role_code, 'ticket.create' AS permission_code
  UNION ALL
  SELECT 'CENTRE_ASSISTANT_COORDINATOR' AS role_code, 'ticket.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_ASSISTANT_COORDINATOR' AS role_code, 'upload.create' AS permission_code
  UNION ALL
  SELECT 'CENTRE_ASSISTANT_COORDINATOR' AS role_code, 'upload.read' AS permission_code
  UNION ALL
  SELECT 'CENTRE_ASSISTANT_COORDINATOR' AS role_code, 'vitals.submit' AS permission_code
  UNION ALL
  SELECT 'CENTRE_PHARMACY' AS role_code, 'centre.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_PHARMACY' AS role_code, 'centre_bundle.mark_treated' AS permission_code
  UNION ALL
  SELECT 'CENTRE_PHARMACY' AS role_code, 'centre_bundle.read' AS permission_code
  UNION ALL
  SELECT 'CENTRE_PHARMACY' AS role_code, 'centre_patient.read' AS permission_code
  UNION ALL
  SELECT 'CENTRE_PHARMACY' AS role_code, 'document.download' AS permission_code
  UNION ALL
  SELECT 'CENTRE_PHARMACY' AS role_code, 'document.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_PHARMACY' AS role_code, 'notification.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_PHARMACY' AS role_code, 'prescription.read' AS permission_code
  UNION ALL
  SELECT 'CENTRE_PHARMACY' AS role_code, 'profile.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_PHARMACY' AS role_code, 'profile.update_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_PHARMACY' AS role_code, 'ticket.create' AS permission_code
  UNION ALL
  SELECT 'CENTRE_PHARMACY' AS role_code, 'ticket.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_LABORATORY' AS role_code, 'centre.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_LABORATORY' AS role_code, 'centre_bundle.mark_treated' AS permission_code
  UNION ALL
  SELECT 'CENTRE_LABORATORY' AS role_code, 'centre_bundle.read' AS permission_code
  UNION ALL
  SELECT 'CENTRE_LABORATORY' AS role_code, 'centre_patient.read' AS permission_code
  UNION ALL
  SELECT 'CENTRE_LABORATORY' AS role_code, 'document.download' AS permission_code
  UNION ALL
  SELECT 'CENTRE_LABORATORY' AS role_code, 'document.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_LABORATORY' AS role_code, 'investigation.read' AS permission_code
  UNION ALL
  SELECT 'CENTRE_LABORATORY' AS role_code, 'notification.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_LABORATORY' AS role_code, 'profile.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_LABORATORY' AS role_code, 'profile.update_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_LABORATORY' AS role_code, 'ticket.create' AS permission_code
  UNION ALL
  SELECT 'CENTRE_LABORATORY' AS role_code, 'ticket.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HIM' AS role_code, 'centre.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HIM' AS role_code, 'centre_bundle.mark_treated' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HIM' AS role_code, 'centre_bundle.read' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HIM' AS role_code, 'centre_patient.read' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HIM' AS role_code, 'centre_patient.update' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HIM' AS role_code, 'centre_report.read' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HIM' AS role_code, 'document.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HIM' AS role_code, 'notification.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HIM' AS role_code, 'profile.read_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HIM' AS role_code, 'profile.update_own' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HIM' AS role_code, 'ticket.create' AS permission_code
  UNION ALL
  SELECT 'CENTRE_HIM' AS role_code, 'ticket.read_own' AS permission_code
) m
JOIN roles       r ON r.code = m.role_code
JOIN permissions p ON p.code = m.permission_code;

-- Invariants for this matrix (no empty role, no orphan permission, no
-- forbidden grant) are asserted by PermissionMatrixTest rather than here.
-- A migration applies changes; a test decides whether the result is correct,
-- and it can say which grant is wrong instead of only that something is.

-- ---------------------------------------------------------------------
-- Migrate the existing single-role column into the join table, then remove
-- it. Any account created before this migration keeps the role it had, as
-- its primary role.
-- ---------------------------------------------------------------------
INSERT INTO user_role (user_id, role_id, created_at, created_by, is_primary, granted_at, granted_by, grant_reason)
SELECT u.id, r.id, UTC_TIMESTAMP(6), 'system', b'1', UTC_TIMESTAMP(6), 'system',
       'Migrated from users.role in V5'
FROM users u
JOIN roles r ON r.code = u.role;

ALTER TABLE users DROP COLUMN role;

-- ---------------------------------------------------------------------
-- centre_staff.role duplicated user_role.
--
-- Two places holding the same fact is two places that can disagree about what
-- a centre pharmacist may do. centre_staff records membership of a centre and
-- the activation audit around it. The capability lives in user_role, which is
-- the single source of truth for authorisation.
-- ---------------------------------------------------------------------
INSERT INTO user_role (user_id, role_id, created_at, created_by, is_primary, granted_at, granted_by, grant_reason)
SELECT cs.user_id, r.id, UTC_TIMESTAMP(6), 'system', b'0', UTC_TIMESTAMP(6), 'system',
       'Migrated from centre_staff.role in V5'
FROM centre_staff cs
JOIN roles r ON r.code = cs.role
WHERE NOT EXISTS (
    SELECT 1 FROM user_role ur WHERE ur.user_id = cs.user_id AND ur.role_id = r.id
);

ALTER TABLE centre_staff DROP COLUMN role;
