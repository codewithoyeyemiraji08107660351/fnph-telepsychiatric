-- =====================================================================
-- FNPH Telepsychiatry - test seed data
--
-- Run AFTER the application has started once and Flyway has applied all
-- 23 migrations. This adds the accounts, rooms, availability and schedule
-- that the API cannot create for itself.
--
--   mysql -u root -p telepsychiatric < seed-test-data.sql
--
-- Everything here is test data. Do not run it against a real deployment.
-- Every password below is the same and is public knowledge.
-- =====================================================================

USE telepsychiatric;

-- ---------------------------------------------------------------------
-- 1. Staff accounts
--
-- The first administrator cannot be created through the API: creating a
-- user needs an authenticated user holding user.create, and nobody has it
-- yet. So the whole staff set is seeded here.
--
-- Password for every account: FnphAdmin!2026Test
-- BCrypt strength 10. Change it after the first sign-in.
--
-- mfa_enabled is 0 so you can sign in without enrolling a second factor
-- first. Enrol it on the administrator once you are in; the permission
-- matrix expects every FNPH staff account to have it.
-- ---------------------------------------------------------------------
SET @pw = '$2b$10$7HmHCVnGao/rjmMxkJxX9.2vaQn5YcFJsXZ5Vvg3il3RNFhMSiEau';

INSERT INTO users
  (created_at, public_id, created_by, username, email, password, first_name, last_name,
   phone_number, is_active, status, mfa_enabled, must_change_password,
   failed_login_attempts, account_locked, login_type, email_verified_at, deleted)
VALUES
  (UTC_TIMESTAMP(6),'01TESTADMIN0000000000001','seed','admin','admin@fnphkaduna.test',@pw,
   'Sade','Adeyemi','08030000001',b'1','ACTIVE',b'0',b'0',0,b'0','USERNAME',UTC_TIMESTAMP(6),b'0'),
  (UTC_TIMESTAMP(6),'01TESTHUB00000000000001','seed','hub.coordinator','hub@fnphkaduna.test',@pw,
   'Ibrahim','Bello','08030000002',b'1','ACTIVE',b'0',b'0',0,b'0','USERNAME',UTC_TIMESTAMP(6),b'0'),
  (UTC_TIMESTAMP(6),'01TESTDOCTOR000000000001','seed','doctor.okafor','doctor@fnphkaduna.test',@pw,
   'Chidi','Okafor','08030000003',b'1','ACTIVE',b'0',b'0',0,b'0','USERNAME',UTC_TIMESTAMP(6),b'0'),
  (UTC_TIMESTAMP(6),'01TESTNURSE0000000000001','seed','nurse.yusuf','nurse@fnphkaduna.test',@pw,
   'Aisha','Yusuf','08030000004',b'1','ACTIVE',b'0',b'0',0,b'0','USERNAME',UTC_TIMESTAMP(6),b'0'),
  (UTC_TIMESTAMP(6),'01TESTPHARM0000000000001','seed','pharmacist.eze','pharmacy@fnphkaduna.test',@pw,
   'Ngozi','Eze','08030000005',b'1','ACTIVE',b'0',b'0',0,b'0','USERNAME',UTC_TIMESTAMP(6),b'0'),
  (UTC_TIMESTAMP(6),'01TESTLAB000000000000001','seed','lab.musa','lab@fnphkaduna.test',@pw,
   'Sani','Musa','08030000006',b'1','ACTIVE',b'0',b'0',0,b'0','USERNAME',UTC_TIMESTAMP(6),b'0'),
  (UTC_TIMESTAMP(6),'01TESTHIM000000000000001','seed','him.abubakar','him@fnphkaduna.test',@pw,
   'Fatima','Abubakar','08030000007',b'1','ACTIVE',b'0',b'0',0,b'0','USERNAME',UTC_TIMESTAMP(6),b'0'),
  (UTC_TIMESTAMP(6),'01TESTFINANCE00000000001','seed','finance.obi','finance@fnphkaduna.test',@pw,
   'Emeka','Obi','08030000008',b'1','ACTIVE',b'0',b'0',0,b'0','USERNAME',UTC_TIMESTAMP(6),b'0'),
  (UTC_TIMESTAMP(6),'01TESTICT000000000000001','seed','ict.danladi','ict@fnphkaduna.test',@pw,
   'Grace','Danladi','08030000009',b'1','ACTIVE',b'0',b'0',0,b'0','USERNAME',UTC_TIMESTAMP(6),b'0'),
  (UTC_TIMESTAMP(6),'01TESTHELP000000000000001','seed','helpdesk.ali','helpdesk@fnphkaduna.test',@pw,
   'Yakubu','Ali','08030000010',b'1','ACTIVE',b'0',b'0',0,b'0','USERNAME',UTC_TIMESTAMP(6),b'0');

-- Role assignment, one primary role each.
INSERT INTO user_role (user_id, role_id, is_primary, granted_at, granted_by, grant_reason,
                       created_at, created_by)
SELECT u.id, r.id, b'1', UTC_TIMESTAMP(6), 'seed', 'Test data seed',
       UTC_TIMESTAMP(6), 'seed'
FROM users u JOIN roles r ON r.code = CASE u.username
    WHEN 'admin'             THEN 'CENTRAL_ADMINISTRATOR'
    WHEN 'hub.coordinator'   THEN 'HUB_COORDINATOR'
    WHEN 'doctor.okafor'     THEN 'DOCTOR'
    WHEN 'nurse.yusuf'       THEN 'NURSING'
    WHEN 'pharmacist.eze'    THEN 'PHARMACIST'
    WHEN 'lab.musa'          THEN 'LABORATORY_TECHNICIAN'
    WHEN 'him.abubakar'      THEN 'HIM'
    WHEN 'finance.obi'       THEN 'FINANCE'
    WHEN 'ict.danladi'       THEN 'ICT_SUPPORT'
    WHEN 'helpdesk.ali'      THEN 'HELPDESK'
END
WHERE u.created_by = 'seed';

-- ---------------------------------------------------------------------
-- 2. Centre staff
--
-- Two coordinators at two DIFFERENT centres. That is deliberate: the
-- cross-tenant isolation check needs an account at centre B that tries to
-- read centre A's data, and one centre is not enough to test it.
-- ---------------------------------------------------------------------
INSERT INTO users
  (created_at, public_id, created_by, username, email, password, first_name, last_name,
   phone_number, is_active, status, mfa_enabled, must_change_password,
   failed_login_attempts, account_locked, login_type, centre_id, email_verified_at, deleted)
SELECT UTC_TIMESTAMP(6),'01TESTCENTREA00000000001','seed','centre.a','centre.a@fnphkaduna.test',@pw,
       'Hauwa','Lawal','08030000011',b'1','ACTIVE',b'0',b'0',0,b'0','USERNAME',
       c.id, UTC_TIMESTAMP(6), b'0'
FROM centres c ORDER BY c.id LIMIT 1;

INSERT INTO users
  (created_at, public_id, created_by, username, email, password, first_name, last_name,
   phone_number, is_active, status, mfa_enabled, must_change_password,
   failed_login_attempts, account_locked, login_type, centre_id, email_verified_at, deleted)
SELECT UTC_TIMESTAMP(6),'01TESTCENTREB00000000001','seed','centre.b','centre.b@fnphkaduna.test',@pw,
       'Tunde','Adebayo','08030000012',b'1','ACTIVE',b'0',b'0',0,b'0','USERNAME',
       c.id, UTC_TIMESTAMP(6), b'0'
FROM centres c ORDER BY c.id LIMIT 1 OFFSET 1;

INSERT INTO user_role (user_id, role_id, is_primary, granted_at, granted_by, grant_reason,
                       created_at, created_by)
SELECT u.id, r.id, b'1', UTC_TIMESTAMP(6), 'seed', 'Test data seed',
       UTC_TIMESTAMP(6), 'seed'
FROM users u JOIN roles r ON r.code = 'CENTRE_HUB_COORDINATOR'
WHERE u.username IN ('centre.a', 'centre.b');

-- Activate the two centres so they can submit referrals, and switch on
-- their optional capabilities.
UPDATE centres SET status = 'ACTIVE', is_active = b'1',
                   activated_at = UTC_TIMESTAMP(6), activated_by = 'seed'
WHERE id IN (SELECT centre_id FROM users WHERE username IN ('centre.a','centre.b'));

UPDATE centre_capabilities SET is_enabled = b'1'
WHERE centre_id IN (SELECT centre_id FROM users WHERE username IN ('centre.a','centre.b'));

-- ---------------------------------------------------------------------
-- 3. Consultation rooms
--
-- Capacity comes from this table. Four patient rooms means four
-- concurrent consultations per period, so a published day generates
-- periods x 4 slots.
-- ---------------------------------------------------------------------
INSERT INTO rooms (created_at, public_id, created_by, code, name, room_type,
                   capacity_notes, is_active)
VALUES
  (UTC_TIMESTAMP(6),'01TESTROOM00000000000001','seed','ROOM-01','Consulting Room 1',
   'PATIENT_SERVICE','Ground floor, near reception',b'1'),
  (UTC_TIMESTAMP(6),'01TESTROOM00000000000002','seed','ROOM-02','Consulting Room 2',
   'PATIENT_SERVICE','Ground floor',b'1'),
  (UTC_TIMESTAMP(6),'01TESTROOM00000000000003','seed','ROOM-03','Consulting Room 3',
   'PATIENT_SERVICE','First floor',b'1'),
  (UTC_TIMESTAMP(6),'01TESTROOM00000000000004','seed','ROOM-04','Consulting Room 4',
   'PATIENT_SERVICE','First floor',b'1'),
  (UTC_TIMESTAMP(6),'01TESTROOM00000000000005','seed','CENTRE-01','Centre Consultation Room',
   'CENTRE_CONSULTATION','Reserved for hub-to-hub sessions',b'1'),
  (UTC_TIMESTAMP(6),'01TESTROOM00000000000006','seed','CONT-01','Contingency Room',
   'CONTINGENCY','Used when a room fails mid-session',b'1');

-- ---------------------------------------------------------------------
-- 4. Doctor availability
--
-- WITHOUT THIS EVERY APPROVAL FAILS. Approval checks the doctor is marked
-- available for the whole slot, and an empty availability table means no
-- doctor can be assigned to anything.
--
-- Seeded for the next 14 days, 08:00 to 17:00, so you are not blocked by
-- the date you happen to test on.
-- ---------------------------------------------------------------------
INSERT INTO doctor_availability
  (created_at, public_id, created_by, doctor_id, service_date, start_at, end_at,
   is_available, reason, set_by)
SELECT UTC_TIMESTAMP(6),
       CONCAT('01TESTAVAIL', LPAD(d.n, 13, '0')),
       'seed',
       u.id,
       DATE_ADD(CURDATE(), INTERVAL d.n DAY),
       TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL d.n DAY), '08:00:00'),
       TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL d.n DAY), '17:00:00'),
       b'1', NULL, 'seed'
FROM users u
CROSS JOIN (
    SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
    UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9
    UNION SELECT 10 UNION SELECT 11 UNION SELECT 12 UNION SELECT 13
) d
WHERE u.username = 'doctor.okafor';

-- ---------------------------------------------------------------------
-- 5. Centre wallet funding
--
-- A centre with no balance cannot have a booking approved. Funding both
-- test centres, and leaving a third unfunded so the insufficient-balance
-- refusal can be tested too.
-- ---------------------------------------------------------------------
INSERT INTO wallet_transactions
  (created_at, public_id, created_by, wallet_id, transaction_reference, direction,
   amount, balance_after, description, source)
SELECT UTC_TIMESTAMP(6),
       CONCAT('01TESTWALLET', LPAD(w.id, 12, '0')),
       'seed', w.id,
       CONCAT('SEED-CREDIT-', w.id), 'CREDIT',
       500000.00, 500000.00,
       'Test programme funding', 'SEED'
FROM wallets w
WHERE w.centre_id IN (SELECT centre_id FROM users WHERE username IN ('centre.a','centre.b'));

-- ---------------------------------------------------------------------
-- 6. Verification
-- ---------------------------------------------------------------------
SELECT 'staff accounts' AS item, COUNT(*) AS count FROM users WHERE created_by = 'seed'
UNION ALL SELECT 'role assignments', COUNT(*) FROM user_role WHERE created_by = 'seed'
UNION ALL SELECT 'rooms', COUNT(*) FROM rooms WHERE created_by = 'seed'
UNION ALL SELECT 'doctor availability days', COUNT(*) FROM doctor_availability WHERE created_by = 'seed'
UNION ALL SELECT 'active centres', COUNT(*) FROM centres WHERE status = 'ACTIVE'
UNION ALL SELECT 'funded wallets', COUNT(*) FROM wallet_transactions WHERE created_by = 'seed';

SELECT u.username, r.code AS role, u.centre_id
FROM users u
JOIN user_role ur ON ur.user_id = u.id
JOIN roles r ON r.id = ur.role_id
WHERE u.created_by = 'seed'
ORDER BY r.scope, r.code;
