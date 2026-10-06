-- =====================================================================
-- V41 - who was on each consultation's team, and every change to it
--
-- Until now the appointment held only the current doctor, nurse,
-- pharmacist, laboratory technician, HIM officer and room. Assigning a new
-- one overwrote the old with no trace, so "who held this review before" had
-- no answer.
--
-- One row per role per change. User and room ids are kept without foreign
-- keys, and their names are copied at the time, so a renamed or deactivated
-- account still reads correctly in the history. The application only inserts.
-- =====================================================================

CREATE TABLE appointment_team_events (
                                         id               BIGINT       NOT NULL AUTO_INCREMENT,
                                         public_id        VARCHAR(26)  NULL,
                                         created_at       DATETIME(6)  NOT NULL,
                                         created_by       VARCHAR(100) NULL,

                                         appointment_id   BIGINT       NOT NULL,
    -- DOCTOR, NURSE, PHARMACIST, LABORATORY, HIM, ROOM
                                         team_role        VARCHAR(20)  NOT NULL,
                                         from_ref_id      BIGINT       NULL,
                                         to_ref_id        BIGINT       NULL,
                                         from_label       VARCHAR(150) NULL,
                                         to_label         VARCHAR(150) NULL,
    -- APPROVAL, ASSIGNMENT, ROOM_CHANGE, RESCHEDULE, BACKFILL
                                         change_source    VARCHAR(20)  NOT NULL,
                                         reason           VARCHAR(500) NULL,
                                         changed_by       VARCHAR(255) NOT NULL,
                                         changed_at       DATETIME(6)  NOT NULL,

                                         PRIMARY KEY (id),
                                         UNIQUE KEY uk_team_events_public_id (public_id),
                                         KEY idx_team_events_appt (appointment_id, changed_at),
                                         KEY idx_team_events_member (team_role, to_ref_id, changed_at),
                                         CONSTRAINT fk_team_events_appt FOREIGN KEY (appointment_id) REFERENCES appointments (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Backfill: the team each existing appointment holds today.
--
-- After approval only the room can change, so for consultations that have
-- happened this is the team that actually ran them. Changes made before
-- approval were never recorded and cannot be recovered.
-- ---------------------------------------------------------------------

INSERT INTO appointment_team_events
(created_at, created_by, appointment_id, team_role, to_ref_id, to_label,
 change_source, reason, changed_by, changed_at)
SELECT UTC_TIMESTAMP(6), 'system', a.id, t.team_role, u.id,
       CONCAT(u.first_name, ' ', u.last_name),
       'BACKFILL', 'Team in place when history began',
       COALESCE(a.approved_by, 'system'), COALESCE(a.approved_at, a.created_at)
FROM appointments a
         JOIN (
    SELECT id AS appointment_id, 'DOCTOR'     AS team_role, doctor_id                AS user_id FROM appointments
    UNION ALL SELECT id, 'NURSE',      nurse_id                 FROM appointments
    UNION ALL SELECT id, 'PHARMACIST', pharmacist_id            FROM appointments
    UNION ALL SELECT id, 'LABORATORY', laboratory_technician_id FROM appointments
    UNION ALL SELECT id, 'HIM',        him_officer_id           FROM appointments
) t ON t.appointment_id = a.id
         JOIN users u ON u.id = t.user_id;

INSERT INTO appointment_team_events
(created_at, created_by, appointment_id, team_role, to_ref_id, to_label,
 change_source, reason, changed_by, changed_at)
SELECT UTC_TIMESTAMP(6), 'system', a.id, 'ROOM', r.id, r.code,
       'BACKFILL', 'Team in place when history began',
       COALESCE(a.approved_by, 'system'), COALESCE(a.approved_at, a.created_at)
FROM appointments a
         JOIN rooms r ON r.id = a.room_id;
