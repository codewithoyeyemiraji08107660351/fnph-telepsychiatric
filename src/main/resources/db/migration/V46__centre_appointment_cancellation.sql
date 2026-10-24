-- V46 - Centre appointment cancellation and wallet reversal
--
-- A centre booking debits the centre's prepaid wallet at approval. Until now
-- nothing could cancel one, and nothing gave the money back when FNPH failed
-- to deliver the consultation. These columns record who cancelled, why, and
-- the ledger entry that returned the charge.
--
-- The refund itself is a new CREDIT entry on the append-only ledger that
-- points at the debit it reverses (reverses_transaction_id). The debit stays
-- POSTED. Marking it REVERSED as well would remove it from the derived balance
-- and the credit would then refund it a second time.

ALTER TABLE centre_appointments
    ADD COLUMN cancelled_at                DATETIME(6)  NULL AFTER no_show_at,
    ADD COLUMN cancelled_by                VARCHAR(100) NULL AFTER cancelled_at,
    ADD COLUMN cancellation_reason         VARCHAR(500) NULL AFTER cancelled_by,
    ADD COLUMN wallet_reversal_id          BIGINT       NULL AFTER cancellation_reason,
    ADD COLUMN wallet_reversed_at          DATETIME(6)  NULL AFTER wallet_reversal_id;

ALTER TABLE centre_appointments
    ADD CONSTRAINT fk_centre_appointments_wallet_reversal FOREIGN KEY (wallet_reversal_id)
        REFERENCES wallet_transactions (id);
