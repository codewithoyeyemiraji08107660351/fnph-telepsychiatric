-- The first account.
--
-- Without it the system cannot be entered at all. Every route to creating a
-- user requires user.create, and the only unauthenticated path is patient
-- enrolment, which needs an active EHR import that an administrator has to
-- activate. V5 seeds sixteen roles and the permission catalogue and not one
-- account to hold any of them, so a clean replay produces a system nobody can
-- sign in to.
--
-- ON THE PASSWORD
--   Temporary, public, and safe only because must_change_password is set. The
--   real password is chosen at first sign-in and never exists in version
--   control. Change the email before running this on anything but a laptop.
--
-- ON WHY THIS IS A MIGRATION
--   It has to survive a clean replay, which is how both databases are built
--   and how the restore rehearsal works. A CommandLineRunner would also work
--   and is one refactor away from silently not running.

INSERT INTO users (
    public_id, created_at, created_by,
    username, email, email_verified_at,
    password,
    first_name, last_name,
    is_active, status,
    account_locked, must_change_password, mfa_enabled,
    failed_login_attempts, login_type, deleted,
    activated_at, password_changed_at
) VALUES (
    '01BOOTSTRAPADMIN000000000',
    NOW(6), 'bootstrap',
    'fnph.admin', 'admin@fnph.local',
    -- Set, so a password reset is possible without an invitation round trip.
    NOW(6),
    -- BCrypt cost 10 of: ChangeMeOnFirstLogin
    '$2a$10$oAyGZsNY4sZj0e70T62VlOAQBInujREDWvdhRPCXF6yWZ.v2ZH1re',
    'FNPH', 'Administrator',
    b'1', 'ACTIVE',
    b'0', b'1', b'0',
    0, 'USERNAME', b'0',
    NOW(6), NOW(6)
);

INSERT INTO user_role (
    user_id, role_id, created_at, created_by,
    is_primary, granted_at, granted_by, grant_reason
)
SELECT u.id, r.id, NOW(6), 'bootstrap',
       b'1', NOW(6), 'bootstrap',
       'Initial administrator, created by migration so the system can be entered'
  FROM users u
  JOIN roles r ON r.code = 'CENTRAL_ADMINISTRATOR'
 WHERE u.username = 'fnph.admin';