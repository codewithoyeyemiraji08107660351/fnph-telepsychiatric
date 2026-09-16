-- The real date of birth on each EHR record, encrypted like the email.
--
-- Patients enrol with their EHR number alone. The record held only a hash of
-- the date of birth (for checking a typed date) and a masked year, so an
-- enrolled patient was given 1 January of their birth year. Imports taken
-- after this migration carry the real date; older imports fall back to the
-- placeholder until a fresh export is uploaded and activated.
--
-- Version 30: 28 and 29 are taken by V28__bootstrap_administrator.sql and the
-- V29 Java backfill.

ALTER TABLE ehr_verification_records
    ADD COLUMN date_of_birth_encrypted VARCHAR(512) NULL;