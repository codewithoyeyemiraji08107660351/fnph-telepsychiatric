-- =====================================================================
-- V7 - centre capabilities as rows, and centre lifecycle
--
-- WHY THE BOOLEANS HAD TO GO
--   centres carried has_pharmacy_capability, has_laboratory_capability and
--   has_him_capability. Activating an optional local role at a centre is a
--   Central Administrator decision that must carry who, when and why: the
--   specification says these are activated "after staffing and capability
--   review". A boolean records the answer and destroys the review.
--
--   Worse, a boolean cannot be un-set with a reason. Turning off a centre's
--   pharmacy capability because the pharmacist left is a different event from
--   turning it off because the centre failed an audit, and only one of those
--   should be quietly reversible.
--
-- CENTRE LIFECYCLE
--   is_active was a single flag covering "not yet set up", "running" and
--   "stopped". Those need to be distinguishable: a centre in SETUP has no
--   coordinator yet and must not appear in a booking list, while a SUSPENDED
--   one has history and staff and must not lose either.
-- =====================================================================

CREATE TABLE centre_capabilities (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    public_id     VARCHAR(26)  NULL,
    created_at    DATETIME(6)  NOT NULL,
    created_by    VARCHAR(100) NULL,
    updated_at    DATETIME(6)  NULL,
    updated_by    VARCHAR(100) NULL,

    centre_id     BIGINT       NOT NULL,
    capability    VARCHAR(30)  NOT NULL,
    is_enabled    BIT(1)       NOT NULL DEFAULT b'0',

    enabled_at    DATETIME(6)  NULL,
    enabled_by    VARCHAR(100) NULL,
    enable_reason VARCHAR(500) NULL,

    disabled_at   DATETIME(6)  NULL,
    disabled_by   VARCHAR(100) NULL,
    disable_reason VARCHAR(500) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_centre_capabilities_public_id (public_id),
    UNIQUE KEY uk_centre_capabilities_centre_capability (centre_id, capability),
    KEY idx_centre_capabilities_centre (centre_id),
    CONSTRAINT fk_centre_capabilities_centre
        FOREIGN KEY (centre_id) REFERENCES centres (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Every centre gets a row per capability, disabled. A missing row and a
-- disabled row would otherwise mean the same thing but read differently, and
-- the administration screen needs something to switch on.
INSERT INTO centre_capabilities (created_at, created_by, centre_id, capability, is_enabled)
SELECT UTC_TIMESTAMP(6), 'system', c.id, cap.name, b'0'
FROM centres c
CROSS JOIN (SELECT 'PHARMACY' AS name
            UNION ALL SELECT 'LABORATORY'
            UNION ALL SELECT 'HIM') cap;

-- Carry across anything already switched on by the old flags.
UPDATE centre_capabilities cc
  JOIN centres c ON c.id = cc.centre_id
   SET cc.is_enabled = b'1',
       cc.enabled_at = UTC_TIMESTAMP(6),
       cc.enabled_by = 'system',
       cc.enable_reason = 'Migrated from the capability flags on centres in V7'
 WHERE (cc.capability = 'PHARMACY'   AND c.has_pharmacy_capability = 1)
    OR (cc.capability = 'LABORATORY' AND c.has_laboratory_capability = 1)
    OR (cc.capability = 'HIM'        AND c.has_him_capability = 1);

ALTER TABLE centres
    DROP COLUMN has_pharmacy_capability,
    DROP COLUMN has_laboratory_capability,
    DROP COLUMN has_him_capability;

-- ---------------------------------------------------------------------
-- Centre lifecycle
-- ---------------------------------------------------------------------
ALTER TABLE centres
    ADD COLUMN status         VARCHAR(20)  NOT NULL DEFAULT 'SETUP' AFTER is_active,
    ADD COLUMN activated_at   DATETIME(6)  NULL AFTER status,
    ADD COLUMN activated_by   VARCHAR(100) NULL AFTER activated_at,
    ADD COLUMN suspended_at   DATETIME(6)  NULL AFTER activated_by,
    ADD COLUMN suspended_by   VARCHAR(100) NULL AFTER suspended_at,
    ADD COLUMN suspend_reason VARCHAR(500) NULL AFTER suspended_by;

UPDATE centres SET status = 'ACTIVE' WHERE is_active = 1;
UPDATE centres SET status = 'SETUP'  WHERE is_active = 0;

CREATE INDEX idx_centres_status ON centres (status);
