-- V27 - make V21's email route deliverable
--
-- V21 added email_hash and email_masked and named email as the preferred
-- route for an enrolment code. Neither can be sent to: a hash proves a match
-- and a mask is for display. So the route V21 was written for could not work,
-- and every enrolment fell through to the phone branch or was refused.
--
-- The address is stored encrypted rather than in plaintext. A readable table
-- of EHR numbers with names and contact details is a directory of who is a
-- psychiatric patient at FNPH, which is the disclosure the hashes exist to
-- prevent. AES-256-GCM via SecretEncryptor, decrypted only at send time and
-- never returned to a caller.
--
-- Ciphertext is base64 of IV plus tag plus payload, so it is longer than the
-- address. 512 leaves room for the longest realistic address.
ALTER TABLE ehr_verification_records
    ADD COLUMN email_encrypted VARCHAR(512) NULL AFTER email_masked;

    ALTER TABLE ehr_verification_records
        ADD COLUMN email_encrypted VARCHAR(512) NULL AFTER email_masked;

    -- Carried from lookup so activation does not invent a date. Matched against
    -- date_of_birth_hash before it is stored, so it is already proved.
    ALTER TABLE contact_verifications
        ADD COLUMN corroborated_date_of_birth DATE NULL AFTER ehr_number;