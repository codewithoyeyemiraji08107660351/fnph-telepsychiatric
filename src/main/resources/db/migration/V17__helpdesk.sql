-- =====================================================================
-- V17 - helpdesk
--
-- THE RISK THIS MODULE CREATES
--   A free-text ticket thread is the easiest place in the system for clinical
--   information to end up where it should not be. A patient types their
--   symptoms into a support form, a helpdesk agent with no clinical permission
--   reads them, and every boundary drawn in the permission matrix has been
--   walked around by a text box.
--
--   Three things answer that here:
--
--   1. Categories are a closed set, and CLINICAL_CONCERN is one of them.
--      A ticket in that category cannot be resolved by the helpdesk. It is
--      escalated, and the emergency guidance is attached automatically.
--
--   2. Messages are marked internal or patient-visible. An internal note is
--      never returned to a patient, so staff can record context without it
--      becoming correspondence.
--
--   3. Nothing in this module joins to a clinical table. A ticket references
--      an appointment or a payment by identifier so an agent can see that a
--      booking exists and what it costs. It cannot reach the consultation
--      note, the prescription or the investigation.
--
-- FIRST RESPONSE IS MEASURED, NOT PROMISED
--   The ten-minute target is configuration. first_responded_at records what
--   actually happened, so the number reported to FNPH is measured rather than
--   asserted.
-- =====================================================================

CREATE TABLE support_tickets (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    public_id             VARCHAR(26)  NULL,
    created_at            DATETIME(6)  NOT NULL,
    created_by            VARCHAR(100) NULL,
    updated_at            DATETIME(6)  NULL,
    updated_by            VARCHAR(100) NULL,

    ticket_number         VARCHAR(30)  NOT NULL,
    category              VARCHAR(30)  NOT NULL,
    priority              VARCHAR(20)  NOT NULL DEFAULT 'NORMAL',
    status                VARCHAR(20)  NOT NULL DEFAULT 'OPEN',

    subject               VARCHAR(200) NOT NULL,

    raised_by_user_id     BIGINT       NULL,
    patient_id            BIGINT       NULL,
    centre_id             BIGINT       NULL,

    -- Reference only. An agent can see that a booking exists and where it got
    -- to. There is no join from here into anything clinical.
    related_appointment_reference VARCHAR(50) NULL,
    related_payment_reference     VARCHAR(50) NULL,
    related_document_number       VARCHAR(50) NULL,

    assigned_to_id        BIGINT       NULL,
    assigned_at           DATETIME(6)  NULL,

    first_responded_at    DATETIME(6)  NULL,
    first_response_minutes INT         NULL,
    resolved_at           DATETIME(6)  NULL,
    resolved_by           VARCHAR(100) NULL,
    resolution_summary    VARCHAR(1000) NULL,
    closed_at             DATETIME(6)  NULL,

    escalated_at          DATETIME(6)  NULL,
    escalated_to_role     VARCHAR(40)  NULL,
    escalation_reason     VARCHAR(500) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_support_tickets_public_id (public_id),
    UNIQUE KEY uk_support_tickets_number (ticket_number),
    KEY idx_support_tickets_queue (status, priority, created_at),
    KEY idx_support_tickets_assignee (assigned_to_id, status),
    KEY idx_support_tickets_patient (patient_id),
    KEY idx_support_tickets_centre (centre_id),
    CONSTRAINT fk_support_tickets_raiser   FOREIGN KEY (raised_by_user_id) REFERENCES users (id),
    CONSTRAINT fk_support_tickets_patient  FOREIGN KEY (patient_id) REFERENCES patients (id),
    CONSTRAINT fk_support_tickets_centre   FOREIGN KEY (centre_id) REFERENCES centres (id),
    CONSTRAINT fk_support_tickets_assignee FOREIGN KEY (assigned_to_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Append-only. A support conversation that can be edited afterwards is not a
-- record of what was said.
CREATE TABLE ticket_messages (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    public_id      VARCHAR(26)  NULL,
    created_at     DATETIME(6)  NOT NULL,
    created_by     VARCHAR(100) NULL,

    ticket_id      BIGINT       NOT NULL,
    author_user_id BIGINT       NULL,
    author_label   VARCHAR(100) NOT NULL,
    body           TEXT         NOT NULL,

    -- Internal notes are never returned to the person who raised the ticket.
    -- Without the distinction, staff either write nothing down or write it
    -- somewhere outside the system.
    is_internal    BIT(1)       NOT NULL DEFAULT b'0',
    sent_at        DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_ticket_messages_public_id (public_id),
    KEY idx_ticket_messages_thread (ticket_id, sent_at),
    CONSTRAINT fk_ticket_messages_ticket FOREIGN KEY (ticket_id)
        REFERENCES support_tickets (id) ON DELETE CASCADE,
    CONSTRAINT fk_ticket_messages_author FOREIGN KEY (author_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Every state change. Append-only, so "who escalated this and when" survives
-- the ticket being closed.
CREATE TABLE ticket_events (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    public_id    VARCHAR(26)  NULL,
    created_at   DATETIME(6)  NOT NULL,
    created_by   VARCHAR(100) NULL,

    ticket_id    BIGINT       NOT NULL,
    event_type   VARCHAR(30)  NOT NULL,
    from_value   VARCHAR(40)  NULL,
    to_value     VARCHAR(40)  NULL,
    actor        VARCHAR(100) NULL,
    occurred_at  DATETIME(6)  NOT NULL,
    detail       VARCHAR(500) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_ticket_events_public_id (public_id),
    KEY idx_ticket_events_ticket (ticket_id, occurred_at),
    CONSTRAINT fk_ticket_events_ticket FOREIGN KEY (ticket_id)
        REFERENCES support_tickets (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- The helpdesk needs its own escalation targets in configuration rather than
-- hard-coded, because who handles a payment query is an operational decision
-- FNPH may change without a release.
INSERT INTO system_configuration
    (created_at, created_by, config_key, config_value, value_type, category,
     description, min_value, max_value, allowed_values, requires_governance,
     is_sensitive, effective_from)
VALUES
 (UTC_TIMESTAMP(6),'system','helpdesk_resolution_target_hours','24','INTEGER','support',
  'Target for resolving a normal-priority ticket','1','168',NULL,b'0',b'0',UTC_TIMESTAMP(6)),
 (UTC_TIMESTAMP(6),'system','helpdesk_clinical_escalation_role','HUB_COORDINATOR','STRING','support',
  'Role a clinical concern is escalated to. The helpdesk never answers one itself.',
  NULL,NULL,NULL,b'1',b'0',UTC_TIMESTAMP(6)),
 (UTC_TIMESTAMP(6),'system','helpdesk_technical_escalation_role','ICT_SUPPORT','STRING','support',
  'Role a technical fault is escalated to',NULL,NULL,NULL,b'0',b'0',UTC_TIMESTAMP(6)),
 (UTC_TIMESTAMP(6),'system','helpdesk_payment_escalation_role','FINANCE','STRING','support',
  'Role a payment discrepancy is escalated to',NULL,NULL,NULL,b'0',b'0',UTC_TIMESTAMP(6));

INSERT INTO configuration_changes
    (created_at, created_by, configuration_id, config_key, previous_value,
     new_value, reason, changed_by, changed_at, effective_from)
SELECT UTC_TIMESTAMP(6), 'system', c.id, c.config_key, NULL, c.config_value,
       'Initial value seeded in V17', 'system', UTC_TIMESTAMP(6), c.effective_from
FROM system_configuration c
WHERE c.config_key LIKE 'helpdesk_%'
  AND NOT EXISTS (SELECT 1 FROM configuration_changes h WHERE h.config_key = c.config_key);
