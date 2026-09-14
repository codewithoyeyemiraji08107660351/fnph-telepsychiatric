-- V27 - make V21's email route deliverable
--
-- V21 added email_hash and email_masked and named email as the preferred
-- route for an enrolment code. Neither can be sent to: a hash proves a match
-- and a mask is for display. So the route V21 was written for could not work.
--
-- Stored encrypted rather than in plaintext. A readable table of EHR numbers
-- with names and contact details is a directory of who is a psychiatric
-- patient at FNPH, which is the disclosure the hashes exist to prevent.
-- AES-256-GCM via SecretEncryptor, decrypted only at send time.
--
-- No AFTER clauses. The first version of this migration used them and failed
-- halfway, leaving email_encrypted applied and the version marked failed,
-- which blocks every subsequent startup. Column order is cosmetic and not
-- worth a migration that depends on the exact shape of a table it does not own.
ALTER TABLE ehr_verification_records
    ADD COLUMN email_encrypted VARCHAR(512) NULL;

-- Carried from lookup so activation does not invent a date of birth. Matched
-- against date_of_birth_hash before it is stored, so it is already proved.
ALTER TABLE contact_verifications
    ADD COLUMN corroborated_date_of_birth DATE NULL;