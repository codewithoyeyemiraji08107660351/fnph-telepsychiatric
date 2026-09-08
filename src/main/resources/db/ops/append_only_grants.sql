-- =====================================================================
-- Append-only enforcement for audit_logs and wallet_transactions.
--
-- Run this ONCE per environment as a database administrator, AFTER the
-- Flyway migrations have run. It is deliberately not a Flyway migration,
-- because the application account must not hold the privilege needed to
-- grant itself write access back.
--
-- This is the evidence the production acceptance gate asks for: the audit
-- trail cannot be altered even by the application, and financial ledger
-- history cannot be rewritten.
--
-- Replace 'fnph_app' and the host to match your deployment.
-- =====================================================================

-- The application account gets everything except UPDATE and DELETE on the
-- two append-only tables.
REVOKE UPDATE, DELETE ON telepsychiatric.audit_logs          FROM 'fnph_app'@'%';
REVOKE UPDATE, DELETE ON telepsychiatric.wallet_transactions FROM 'fnph_app'@'%';

GRANT SELECT, INSERT ON telepsychiatric.audit_logs           TO 'fnph_app'@'%';
GRANT SELECT, INSERT ON telepsychiatric.wallet_transactions  TO 'fnph_app'@'%';

FLUSH PRIVILEGES;

-- Verify. Both statements must fail when run as fnph_app.
--   UPDATE audit_logs SET action = 'tampered' WHERE id = 1;
--   DELETE FROM wallet_transactions WHERE id = 1;
