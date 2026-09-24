-- Synchronous invocation, part 2: the request side. One row per invocation, keyed for retries by
-- (workflow_definition_id, idempotency_key) — the unique constraint IS the idempotency: of two
-- concurrent requests with one key exactly one insert commits, and the other joins it. The answer
-- is not here; it is on the process row (V31), written with the transition that produced it.
--
-- Plain portable DDL, as V29-V31: it also runs over a schema ddl-auto created from InvocationEntity.
CREATE TABLE IF NOT EXISTS sync_invocation (
    id                     VARCHAR(255) NOT NULL PRIMARY KEY,
    workflow_definition_id VARCHAR(255) NOT NULL,
    idempotency_key        VARCHAR(255) NOT NULL,
    request_hash           VARCHAR(64),
    process_id             VARCHAR(255) NOT NULL,
    deadline_at            TIMESTAMP,
    created_at             TIMESTAMP,
    expires_at             TIMESTAMP,
    caller_trace_parent    VARCHAR(64),
    CONSTRAINT uk_sync_invocation_key UNIQUE (workflow_definition_id, idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_sync_invocation_expires ON sync_invocation (expires_at);
CREATE INDEX IF NOT EXISTS idx_sync_invocation_process ON sync_invocation (process_id);
