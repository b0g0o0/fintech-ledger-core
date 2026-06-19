-- =============================================================================
-- V7: audit_logs
-- Append-only. Written in Propagation.REQUIRES_NEW — survives TX rollback.
-- actor_id nullable: pre-auth actions (failed login, signup) have no actor.
-- =============================================================================
CREATE TABLE audit_logs (
    id           UUID         NOT NULL DEFAULT gen_random_uuid(),
    actor_id     UUID,                          -- FK not enforced: actor may be deleted
    actor_email  VARCHAR(255),
    action       VARCHAR(100) NOT NULL,
    target_type  VARCHAR(50),                   -- 'USER' | 'ACCOUNT' | 'TRANSACTION'
    target_id    UUID,
    result       VARCHAR(10)  NOT NULL,
    context      JSONB,
    ip_address   VARCHAR(45),                   -- supports IPv6
    user_agent   TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_audit_logs   PRIMARY KEY (id),
    CONSTRAINT chk_audit_result CHECK (result IN ('SUCCESS','FAILURE'))
);

CREATE INDEX idx_audit_actor_created  ON audit_logs (actor_id, created_at DESC);
CREATE INDEX idx_audit_action_created ON audit_logs (action, created_at DESC);
CREATE INDEX idx_audit_target         ON audit_logs (target_type, target_id);
CREATE INDEX idx_audit_created_at     ON audit_logs (created_at DESC);
