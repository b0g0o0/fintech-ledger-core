-- =============================================================================
-- V2: refresh_tokens
-- =============================================================================
CREATE TABLE refresh_tokens (
    id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL,
    token_hash  VARCHAR(255) NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_refresh_tokens          PRIMARY KEY (id),
    CONSTRAINT uq_refresh_tokens_hash     UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user     FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_refresh_tokens_user_id    ON refresh_tokens (user_id);
-- partial index: only index active tokens
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at)
    WHERE revoked = FALSE;
