-- V26 - Nursing and HIM work queue state
--
-- Module 1 requires both dashboards to separate Untreated, In progress,
-- Treated and Exception with timestamps. Only the completion timestamps
-- existed, and nothing wrote them, so neither role could finish its step.
--
-- Derived rather than stored as a status column: a status column and a set of
-- timestamps disagree eventually, and the timestamps are the evidence.
ALTER TABLE appointments
    ADD COLUMN nursing_started_at        DATETIME(6)  NULL AFTER nursing_completed_at,
    ADD COLUMN nursing_exception_at      DATETIME(6)  NULL AFTER nursing_started_at,
    ADD COLUMN nursing_exception_reason  VARCHAR(500) NULL AFTER nursing_exception_at,
    ADD COLUMN him_started_at            DATETIME(6)  NULL AFTER him_completed_at,
    ADD COLUMN him_exception_at          DATETIME(6)  NULL AFTER him_started_at,
    ADD COLUMN him_exception_reason      VARCHAR(500) NULL AFTER him_exception_at;