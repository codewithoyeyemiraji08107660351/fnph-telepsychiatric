-- =====================================================================
-- V12 - schedules, slots, rooms, availability, appointment lifecycle
--
-- THE APPROVED SEQUENCE, AS CONFIRMED BY FNPH
--
--   1. Patient chooses a slot          -> slot HELD, appointment PENDING_PAYMENT
--   2. Patient pays                    -> Remita, verified server-side
--   3. Payment confirmed               -> patient wallet credited,
--                                         slot BOOKED, appointment AWAITING_APPROVAL,
--                                         Hub Coordinator dashboard notified
--   4. Hub Coordinator reviews         -> approves and assigns doctor, nurse,
--                                         room, pharmacy, laboratory, HIM
--                                      -> wallet debited, every assignee notified
--                                      -> or rejects: slot released, wallet keeps
--                                         the balance for the next booking
--
-- THIS INVERTS THE ORDER IN THE PROTOTYPE DOCUMENT
--   The PDF has payment at step 5 and slot selection at step 6. FNPH have
--   confirmed the reverse: choose the time, then pay for it. That is better
--   for the patient, who can see what they are buying, and it makes the
--   no-refund rule far easier to live with because nobody pays for a time
--   that turned out to be unavailable.
--
--   It has one consequence, and slot_holds is the answer to it. An unpaid
--   request would otherwise sit on a slot forever and quietly starve the
--   schedule. A hold expires, the slot returns to the pool, and the
--   appointment expires with it.
--
-- WHY SLOT STATE NEEDS OPTIMISTIC LOCKING
--   Two patients pressing the same time at the same moment is an acceptance
--   gate. The version column makes one of them win and the other get a clear
--   rejection, rather than both being told they have the appointment.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Rooms
--
-- Whether a room limits concurrent appointments is still an open question
-- with FNPH. Modelled as a resource now so the answer is a configuration
-- change rather than a rewrite.
-- ---------------------------------------------------------------------
CREATE TABLE rooms (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    public_id      VARCHAR(26)  NULL,
    created_at     DATETIME(6)  NOT NULL,
    created_by     VARCHAR(100) NULL,
    updated_at     DATETIME(6)  NULL,
    updated_by     VARCHAR(100) NULL,

    code           VARCHAR(20)  NOT NULL,
    name           VARCHAR(100) NOT NULL,
    room_type      VARCHAR(30)  NOT NULL,
    is_active      BIT(1)       NOT NULL DEFAULT b'1',
    capacity_notes VARCHAR(255) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_rooms_public_id (public_id),
    UNIQUE KEY uk_rooms_code (code),
    KEY idx_rooms_type (room_type, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO rooms (created_at, created_by, code, name, room_type, is_active, capacity_notes) VALUES
 (UTC_TIMESTAMP(6),'system','ROOM-01','Room 01','PATIENT_SERVICE',b'1','Telepsychiatry consultation room'),
 (UTC_TIMESTAMP(6),'system','ROOM-02','Room 02','PATIENT_SERVICE',b'1','Telepsychiatry consultation room'),
 (UTC_TIMESTAMP(6),'system','ROOM-03','Room 03','CENTRE_CONSULTATION',b'1','Hub-to-hub centre consultations'),
 (UTC_TIMESTAMP(6),'system','ROOM-04','Room 04','PATIENT_SERVICE',b'1','Telepsychiatry consultation room'),
 (UTC_TIMESTAMP(6),'system','ROOM-CT','Contingency Room','CONTINGENCY',b'1','Used when an assigned room is unavailable');

-- ---------------------------------------------------------------------
-- Doctor availability
-- ---------------------------------------------------------------------
CREATE TABLE doctor_availability (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    public_id     VARCHAR(26)  NULL,
    created_at    DATETIME(6)  NOT NULL,
    created_by    VARCHAR(100) NULL,
    updated_at    DATETIME(6)  NULL,
    updated_by    VARCHAR(100) NULL,

    doctor_id     BIGINT       NOT NULL,
    service_date  DATE         NOT NULL,
    start_at      DATETIME(6)  NOT NULL,
    end_at        DATETIME(6)  NOT NULL,
    is_available  BIT(1)       NOT NULL DEFAULT b'1',
    reason        VARCHAR(255) NULL,
    set_by        VARCHAR(100) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_doctor_availability_public_id (public_id),
    KEY idx_doctor_availability_lookup (doctor_id, service_date),
    CONSTRAINT fk_doctor_availability_doctor FOREIGN KEY (doctor_id) REFERENCES users (id),
    CONSTRAINT ck_doctor_availability_window CHECK (end_at > start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Published consultation days
--
-- Slots are generated from a publication rather than created one at a time,
-- so a day either exists with a full grid or does not exist at all. A
-- half-published day shows a patient two available times out of sixteen and
-- looks like a fully booked clinic.
-- ---------------------------------------------------------------------
CREATE TABLE schedule_publications (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    public_id     VARCHAR(26)  NULL,
    created_at    DATETIME(6)  NOT NULL,
    created_by    VARCHAR(100) NULL,
    updated_at    DATETIME(6)  NULL,
    updated_by    VARCHAR(100) NULL,

    audience      VARCHAR(20)  NOT NULL,
    service_date  DATE         NOT NULL,
    window_start  TIME(6)      NOT NULL,
    window_end    TIME(6)      NOT NULL,
    slot_minutes  INT          NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    published_by  VARCHAR(100) NULL,
    published_at  DATETIME(6)  NULL,
    withdrawn_at  DATETIME(6)  NULL,
    withdraw_reason VARCHAR(500) NULL,
    slots_generated INT        NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uk_schedule_publications_public_id (public_id),
    UNIQUE KEY uk_schedule_publications_audience_date (audience, service_date),
    KEY idx_schedule_publications_date (service_date, status),
    CONSTRAINT ck_schedule_window CHECK (window_end > window_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Slots
-- ---------------------------------------------------------------------
CREATE TABLE slots (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    public_id      VARCHAR(26)  NULL,
    created_at     DATETIME(6)  NOT NULL,
    created_by     VARCHAR(100) NULL,
    updated_at     DATETIME(6)  NULL,
    updated_by     VARCHAR(100) NULL,

    publication_id BIGINT       NOT NULL,
    start_at       DATETIME(6)  NOT NULL,
    end_at         DATETIME(6)  NOT NULL,
    state          VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE',
    room_id        BIGINT       NULL,
    blocked_reason VARCHAR(255) NULL,

    -- Two patients pressing the same time at the same moment. One wins, the
    -- other gets a clear rejection rather than both being told they have it.
    version        BIGINT       NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uk_slots_public_id (public_id),
    UNIQUE KEY uk_slots_publication_start_room (publication_id, start_at, room_id),
    KEY idx_slots_state (state, start_at),
    KEY idx_slots_start (start_at),
    CONSTRAINT fk_slots_publication FOREIGN KEY (publication_id)
        REFERENCES schedule_publications (id) ON DELETE CASCADE,
    CONSTRAINT fk_slots_room FOREIGN KEY (room_id) REFERENCES rooms (id),
    CONSTRAINT ck_slots_window CHECK (end_at > start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- A temporary reservation while the patient pays.
--
-- Without an expiry, an abandoned payment holds a clinic slot forever and the
-- schedule quietly starves. With one, the slot returns to the pool and the
-- appointment expires with it.
CREATE TABLE slot_holds (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    public_id         VARCHAR(26)  NULL,
    created_at        DATETIME(6)  NOT NULL,
    created_by        VARCHAR(100) NULL,
    updated_at        DATETIME(6)  NULL,
    updated_by        VARCHAR(100) NULL,

    slot_id           BIGINT       NOT NULL,
    held_for_patient_id BIGINT     NULL,
    held_for_centre_id  BIGINT     NULL,
    held_at           DATETIME(6)  NOT NULL,
    expires_at        DATETIME(6)  NOT NULL,
    released_at       DATETIME(6)  NULL,
    release_reason    VARCHAR(200) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_slot_holds_public_id (public_id),
    KEY idx_slot_holds_slot (slot_id, released_at),
    KEY idx_slot_holds_expiry (expires_at, released_at),
    CONSTRAINT fk_slot_holds_slot    FOREIGN KEY (slot_id) REFERENCES slots (id) ON DELETE CASCADE,
    CONSTRAINT fk_slot_holds_patient FOREIGN KEY (held_for_patient_id) REFERENCES patients (id),
    CONSTRAINT fk_slot_holds_centre  FOREIGN KEY (held_for_centre_id) REFERENCES centres (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Appointment: bind to a slot and to the payment that bought it
-- ---------------------------------------------------------------------
ALTER TABLE appointments
    ADD COLUMN slot_id      BIGINT      NULL AFTER patient_id,
    ADD COLUMN payment_id   BIGINT      NULL AFTER slot_id,
    ADD COLUMN room_id      BIGINT      NULL AFTER room,
    ADD COLUMN held_until   DATETIME(6) NULL AFTER join_window_opens_at,
    ADD COLUMN wallet_debited_at DATETIME(6) NULL AFTER approved_at;

-- One appointment per slot. This is what makes double booking impossible even
-- if two requests survive every check above it.
CREATE UNIQUE INDEX uk_appointments_slot ON appointments (slot_id);

ALTER TABLE appointments
    ADD CONSTRAINT fk_appointments_slot    FOREIGN KEY (slot_id) REFERENCES slots (id),
    ADD CONSTRAINT fk_appointments_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    ADD CONSTRAINT fk_appointments_room_id FOREIGN KEY (room_id) REFERENCES rooms (id);

-- ---------------------------------------------------------------------
-- Status history
--
-- The appointment row holds only the current status. Reconstructing who
-- approved what and when is an acceptance requirement, and the current value
-- cannot answer it.
-- ---------------------------------------------------------------------
CREATE TABLE appointment_status_history (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    public_id             VARCHAR(26)  NULL,
    created_at            DATETIME(6)  NOT NULL,
    created_by            VARCHAR(100) NULL,

    appointment_id        BIGINT       NULL,
    centre_appointment_id BIGINT       NULL,
    from_status           VARCHAR(30)  NULL,
    to_status             VARCHAR(30)  NOT NULL,
    changed_by            VARCHAR(100) NULL,
    changed_at            DATETIME(6)  NOT NULL,
    reason                VARCHAR(500) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_appointment_history_public_id (public_id),
    KEY idx_appointment_history_appt (appointment_id, changed_at),
    KEY idx_appointment_history_centre (centre_appointment_id, changed_at),
    CONSTRAINT fk_appointment_history_appt   FOREIGN KEY (appointment_id) REFERENCES appointments (id),
    CONSTRAINT fk_appointment_history_centre FOREIGN KEY (centre_appointment_id) REFERENCES centre_appointments (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Cancellation and reschedule
--
-- The 24-hour rule lives in the service, checked against configuration, not
-- hard-coded here. But the request itself is a record: a patient who asked in
-- time and was refused must be able to show they asked.
-- ---------------------------------------------------------------------
CREATE TABLE cancellation_requests (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    public_id         VARCHAR(26)  NULL,
    created_at        DATETIME(6)  NOT NULL,
    created_by        VARCHAR(100) NULL,
    updated_at        DATETIME(6)  NULL,
    updated_by        VARCHAR(100) NULL,

    appointment_id    BIGINT       NOT NULL,
    request_type      VARCHAR(20)  NOT NULL,
    requested_by      VARCHAR(100) NOT NULL,
    requested_at      DATETIME(6)  NOT NULL,
    reason            VARCHAR(500) NOT NULL,
    proposed_slot_id  BIGINT       NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'SUBMITTED',
    decided_by        VARCHAR(100) NULL,
    decided_at        DATETIME(6)  NULL,
    decision_notes    VARCHAR(500) NULL,
    hours_notice      INT          NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_cancellation_requests_public_id (public_id),
    KEY idx_cancellation_requests_status (status, requested_at),
    CONSTRAINT fk_cancellation_requests_appt FOREIGN KEY (appointment_id) REFERENCES appointments (id),
    CONSTRAINT fk_cancellation_requests_slot FOREIGN KEY (proposed_slot_id) REFERENCES slots (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
