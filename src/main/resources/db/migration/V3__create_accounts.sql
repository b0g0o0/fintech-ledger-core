-- =============================================================================
-- V3: accounts
-- =============================================================================
CREATE TABLE accounts (
    id               UUID           NOT NULL DEFAULT gen_random_uuid(),
    user_id          UUID           NOT NULL,
    account_number   VARCHAR(16)    NOT NULL,
    account_name     VARCHAR(100)   NOT NULL,
    currency         VARCHAR(3)     NOT NULL DEFAULT 'KRW',
    status           VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    cached_balance   NUMERIC(19,2)  NOT NULL DEFAULT 0.00,
    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT pk_accounts                   PRIMARY KEY (id),
    CONSTRAINT uq_accounts_account_number    UNIQUE (account_number),
    CONSTRAINT fk_accounts_user              FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_accounts_status           CHECK (status   IN ('ACTIVE','FROZEN','CLOSED')),
    CONSTRAINT chk_accounts_currency         CHECK (currency IN ('KRW','USD','EUR')),
    CONSTRAINT chk_accounts_balance_positive CHECK (cached_balance >= 0)
);

CREATE INDEX idx_accounts_user_id       ON accounts (user_id);
CREATE INDEX idx_accounts_number        ON accounts (account_number);
CREATE INDEX idx_accounts_status        ON accounts (status);
