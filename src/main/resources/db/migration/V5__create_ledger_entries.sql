-- =============================================================================
-- V5: ledger_entries
-- Immutable after INSERT — no UPDATE or DELETE ever issued on this table.
-- Every transaction produces ≥ 2 entries; SUM(signed_amount) per tx = 0.
-- =============================================================================
CREATE TABLE ledger_entries (
    id              UUID           NOT NULL DEFAULT gen_random_uuid(),
    transaction_id  UUID           NOT NULL,
    account_id      UUID           NOT NULL,
    entry_type      VARCHAR(6)     NOT NULL,   -- 'CREDIT' | 'DEBIT'
    amount          NUMERIC(19,2)  NOT NULL,
    running_balance NUMERIC(19,2)  NOT NULL,
    currency        VARCHAR(3)     NOT NULL DEFAULT 'KRW',
    description     VARCHAR(255),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT pk_ledger_entries         PRIMARY KEY (id),
    CONSTRAINT fk_ledger_transaction     FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT fk_ledger_account         FOREIGN KEY (account_id)     REFERENCES accounts (id),
    CONSTRAINT chk_ledger_entry_type     CHECK (entry_type IN ('CREDIT','DEBIT')),
    CONSTRAINT chk_ledger_amount         CHECK (amount > 0)
);

CREATE INDEX idx_ledger_account_created ON ledger_entries (account_id, created_at DESC);
CREATE INDEX idx_ledger_tx_id           ON ledger_entries (transaction_id);
