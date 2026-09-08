-- =====================================================================
-- V4 — centre_id on every tenant-owned table
--
-- WHY
--   Tenant isolation has to be enforced in one place: the repository layer,
--   as a filter applied to every query against a tenant-owned table. That is
--   only possible if the tenant key is ON the row.
--
--   Six tables did not have it. centre_consultations could only be scoped by
--   joining through centre_appointments; centre_consultation_notes by joining
--   through two tables. Scoping that depends on a join is scoping that fails
--   open the first time someone writes a query without the join, and it fails
--   silently, returning another centre's clinical records rather than an error.
--
--   Denormalising the tenant key is the standard answer and the cost is one
--   BIGINT per row. It also makes the isolation test suite able to assert a
--   property of the schema rather than a property of every query ever written.
--
-- ON NULLABILITY
--   centre_vitals, centre_consultations and centre_consultation_notes are
--   intrinsically centre-owned, so centre_id is NOT NULL there.
--
--   prescriptions, investigations and follow_ups are dual-owned: the same
--   table serves both the FNPH and the Centre pathway. centre_id is NULL for
--   an FNPH-pathway row and set for a Centre-pathway row. The repository
--   filter for centre users is "centre_id = :centreId", which excludes FNPH
--   rows automatically rather than by remembering to.
--
--   Each ALTER backfills from the parent before tightening, so this migration
--   is correct against a populated database as well as an empty one.
-- =====================================================================

-- --- centre_vitals -----------------------------------------------------
ALTER TABLE centre_vitals ADD COLUMN centre_id BIGINT NULL AFTER id;
UPDATE centre_vitals v
  JOIN centre_patients p ON p.id = v.centre_patient_id
   SET v.centre_id = p.centre_id;
ALTER TABLE centre_vitals MODIFY COLUMN centre_id BIGINT NOT NULL;
ALTER TABLE centre_vitals
  ADD CONSTRAINT fk_centre_vitals_centre FOREIGN KEY (centre_id) REFERENCES centres (id);
CREATE INDEX idx_centre_vitals_centre ON centre_vitals (centre_id);

-- --- centre_consultations ----------------------------------------------
ALTER TABLE centre_consultations ADD COLUMN centre_id BIGINT NULL AFTER id;
UPDATE centre_consultations c
  JOIN centre_appointments a ON a.id = c.centre_appointment_id
   SET c.centre_id = a.centre_id;
ALTER TABLE centre_consultations MODIFY COLUMN centre_id BIGINT NOT NULL;
ALTER TABLE centre_consultations
  ADD CONSTRAINT fk_centre_consult_centre FOREIGN KEY (centre_id) REFERENCES centres (id);
CREATE INDEX idx_centre_consultations_centre ON centre_consultations (centre_id);

-- --- centre_consultation_notes -----------------------------------------
ALTER TABLE centre_consultation_notes ADD COLUMN centre_id BIGINT NULL AFTER id;
UPDATE centre_consultation_notes n
  JOIN centre_consultations c ON c.id = n.centre_consultation_id
   SET n.centre_id = c.centre_id;
ALTER TABLE centre_consultation_notes MODIFY COLUMN centre_id BIGINT NOT NULL;
ALTER TABLE centre_consultation_notes
  ADD CONSTRAINT fk_centre_notes_centre FOREIGN KEY (centre_id) REFERENCES centres (id);
CREATE INDEX idx_centre_notes_centre ON centre_consultation_notes (centre_id);

-- --- Dual-owned clinical outputs: NULL means the FNPH pathway ----------
ALTER TABLE prescriptions ADD COLUMN centre_id BIGINT NULL AFTER id;
UPDATE prescriptions r
  JOIN centre_patients p ON p.id = r.centre_patient_id
   SET r.centre_id = p.centre_id;
ALTER TABLE prescriptions
  ADD CONSTRAINT fk_prescriptions_centre FOREIGN KEY (centre_id) REFERENCES centres (id);
CREATE INDEX idx_prescriptions_centre ON prescriptions (centre_id);

ALTER TABLE investigations ADD COLUMN centre_id BIGINT NULL AFTER id;
UPDATE investigations i
  JOIN centre_patients p ON p.id = i.centre_patient_id
   SET i.centre_id = p.centre_id;
ALTER TABLE investigations
  ADD CONSTRAINT fk_investigations_centre FOREIGN KEY (centre_id) REFERENCES centres (id);
CREATE INDEX idx_investigations_centre ON investigations (centre_id);

ALTER TABLE follow_ups ADD COLUMN centre_id BIGINT NULL AFTER id;
UPDATE follow_ups f
  JOIN centre_patients p ON p.id = f.centre_patient_id
   SET f.centre_id = p.centre_id;
ALTER TABLE follow_ups
  ADD CONSTRAINT fk_follow_ups_centre FOREIGN KEY (centre_id) REFERENCES centres (id);
CREATE INDEX idx_follow_ups_centre ON follow_ups (centre_id);

-- ---------------------------------------------------------------------
-- Consistency guards.
--
-- The denormalised key can only help if it agrees with the pathway the row
-- actually belongs to. These make disagreement impossible rather than
-- unlikely: a Centre-pathway clinical output must carry a centre, and an
-- FNPH-pathway one must not.
-- ---------------------------------------------------------------------
ALTER TABLE prescriptions ADD CONSTRAINT ck_prescriptions_centre_matches_pathway CHECK (
    (centre_consultation_id IS NOT NULL AND centre_id IS NOT NULL)
 OR (centre_consultation_id IS NULL     AND centre_id IS NULL)
);

ALTER TABLE investigations ADD CONSTRAINT ck_investigations_centre_matches_pathway CHECK (
    (centre_consultation_id IS NOT NULL AND centre_id IS NOT NULL)
 OR (centre_consultation_id IS NULL     AND centre_id IS NULL)
);

ALTER TABLE follow_ups ADD CONSTRAINT ck_follow_ups_centre_matches_pathway CHECK (
    (centre_consultation_id IS NOT NULL AND centre_id IS NOT NULL)
 OR (centre_consultation_id IS NULL     AND centre_id IS NULL)
);
