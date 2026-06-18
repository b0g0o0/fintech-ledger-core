-- =============================================================================
-- V6: outbox_events
-- Written in the SAME DB transaction as domain changes (no dual-write).
-- Polled by OutboxProcessor with FOR UPDATE SKIP LOCKED.
-- =============================================================================
CREATE TABLE outbox_events (
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(50)  NOT NULL,    -- 'TRANSACTION' | 'ACCOUNT' | 'USER'
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(100) NOT NULL,    -- e.g. 'TransferCompleted'
    payload         JSONB        NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count     INT          NOT NULL DEFAULT 0,
    scheduled_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_outbox_events  PRIMARY KEY (id),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING','PROCESSING','SENT','FAILED'))
);

-- Partial index: only PENDING rows matter for the scheduler hot path
CREATE INDEX idx_outbox_pending   ON outbox_events (scheduled_at ASC) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_aggregate ON outbox_events (aggregate_type, aggregate_id);
