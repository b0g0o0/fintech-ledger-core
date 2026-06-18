-- =============================================================================
-- V4: transactions
-- =============================================================================
CREATE TABLE transactions (
    id                  UUID           NOT NULL DEFAULT gen_random_uuid(),
    idempotency_key     VARCHAR(64)    NOT NULL,
    type                VARCHAR(20)    NOT NULL,
    status              VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    source_account_id   UUID,
    target_account_id   UUID,
    amount              NUMERIC(19,2)  NOT NULL,
    currency            VARCHAR(3)     NOT NULL DEFAULT 'KRW',
    description         VARCHAR(255),
    failure_reason      VARCHAR(500),
    request_payload     JSONB,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,

    CONSTRAINT pk_transactions              PRIMARY KEY (id),
    CONSTRAINT uq_transactions_idem_key     UNIQUE (idempotency_key),
    CONSTRAINT fk_transactions_source       FOREIGN KEY (source_account_id) REFERENCES accounts (id),
    CONSTRAINT fk_transactions_target       FOREIGN KEY (target_account_id) REFERENCES accounts (id),
    CONSTRAINT chk_transactions_type        CHECK (type   IN ('DEPOSIT','WITHDRAWAL','TRANSFER')),
    CONSTRAINT chk_transactions_status      CHECK (status IN ('PENDING','PROCESSING','SUCCESS','FAILED','CANCELED')),
    CONSTRAINT chk_transactions_amount      CHECK (amount > 0),
    -- DEPOSIT  : target required
    CONSTRAINT chk_tx_deposit    CHECK (type <> 'DEPOSIT'    OR target_account_id IS NOT NULL),
    -- WITHDRAWAL: source required
    CONSTRAINT chk_tx_withdrawal CHECK (type <> 'WITHDRAWAL' OR source_account_id IS NOT NULL),
    -- TRANSFER : both required
    CONSTRAINT chk_tx_transfer   CHECK (type <> 'TRANSFER'   OR
        (source_account_id IS NOT NULL AND target_account_id IS NOT NULL))
);

CREATE INDEX idx_tx_source_account  ON transactions (source_account_id, created_at DESC);
CREATE INDEX idx_tx_target_account  ON transactions (target_account_id, created_at DESC);
CREATE INDEX idx_tx_status          ON transactions (status);
CREATE INDEX idx_tx_created_at      ON transactions (created_at DESC);
