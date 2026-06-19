-- =============================================================================
-- V8: ledger_entries — unique constraint to prevent duplicate entries
--
-- Prevents a bug where the same (transaction_id, account_id, entry_type)
-- combination could be inserted twice due to a retry or code defect.
-- Without this constraint, the DB cannot detect such duplicates.
-- =============================================================================

ALTER TABLE ledger_entries
    ADD CONSTRAINT uq_ledger_tx_account_type
        UNIQUE (transaction_id, account_id, entry_type);
