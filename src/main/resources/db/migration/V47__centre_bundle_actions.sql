-- V47 - What each centre service did with a delivered care bundle
--
-- A receipt had one treated_at. The pharmacy, laboratory, records and the
-- coordinator all worked from the same list, so whichever marked the bundle
-- treated first removed it from everyone else's: the pharmacy dispenses and
-- the laboratory never sees the investigation request.
--
-- One row per service per bundle. The coordinator's row still sets the
-- receipt's treated_at, which is what "bundles awaiting action" counts.

CREATE TABLE centre_bundle_actions (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    public_id     VARCHAR(26)  NULL,
    created_at    DATETIME(6)  NOT NULL,
    created_by    VARCHAR(100) NULL,
    updated_at    DATETIME(6)  NULL,
    updated_by    VARCHAR(100) NULL,
    centre_id     BIGINT       NOT NULL,
    receipt_id    BIGINT       NOT NULL,
    capability    VARCHAR(20)  NOT NULL,
    acted_at      DATETIME(6)  NOT NULL,
    acted_by      VARCHAR(100) NOT NULL,
    notes         TEXT         NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_centre_bundle_actions_public_id (public_id),
    UNIQUE KEY uk_centre_bundle_actions_receipt_capability (receipt_id, capability),
    KEY idx_centre_bundle_actions_centre (centre_id),
    CONSTRAINT fk_centre_bundle_actions_centre  FOREIGN KEY (centre_id)  REFERENCES centres (id),
    CONSTRAINT fk_centre_bundle_actions_receipt FOREIGN KEY (receipt_id) REFERENCES centre_bundle_receipts (id),
    CONSTRAINT chk_centre_bundle_actions_capability
        CHECK (capability IN ('COORDINATION', 'PHARMACY', 'LABORATORY', 'HIM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Bundles already marked treated keep that as the coordinator's action.
INSERT INTO centre_bundle_actions
    (created_at, created_by, centre_id, receipt_id, capability, acted_at, acted_by, notes)
SELECT UTC_TIMESTAMP(6), 'V47', r.centre_id, r.id, 'COORDINATION', r.treated_at,
       COALESCE(r.treated_by, 'unknown'), COALESCE(r.treatment_notes, '')
FROM centre_bundle_receipts r
WHERE r.treated_at IS NOT NULL;
