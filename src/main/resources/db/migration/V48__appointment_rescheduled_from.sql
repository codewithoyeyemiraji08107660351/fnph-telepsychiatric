-- V48 - Rebooking a patient who missed their consultation
--
-- A missed (NO_SHOW) appointment keeps its own record: its consultation row,
-- attendance and outcome are evidence of what happened and must not be
-- overwritten by a second attempt. The Hub Coordinator's rebooking is a new
-- appointment that points back at the missed one.
--
-- Unique, so a missed appointment can be rebooked once. A second rebooking
-- is a new paid request, not a free one.

ALTER TABLE appointments
    ADD COLUMN rescheduled_from_id BIGINT NULL,
    ADD CONSTRAINT fk_appointments_rescheduled_from
        FOREIGN KEY (rescheduled_from_id) REFERENCES appointments (id);

CREATE UNIQUE INDEX uk_appointments_rescheduled_from ON appointments (rescheduled_from_id);
