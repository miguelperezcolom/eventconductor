-- Process updates (2.33): validated writes to a live process that answer with a result. One row per
-- update, keyed for retries by (process_id, idempotency_key) — the unique constraint IS the idempotency.
-- The row is written REQUESTED at the door and finished (COMPLETED / REFUSED / NOT_ACCEPTED / FAILED,
-- with the result) by the node that owns the process, under its lock and in its transaction. The
-- payload kept here is the masked copy; the raw one only travels in the command.
--
-- Rows live as long as the process (the retention purge deletes them with it); the idempotency key
-- lives workflow.sync.retention, after which it is rewritten to the row id (expires_at nulled).
--
-- V55, not the next number on main (V50): #557 holds V53 and V54 is left for the flow-control work.
-- Plain portable DDL, as V32: it also runs over a schema ddl-auto created from ProcessUpdateEntity.
CREATE TABLE IF NOT EXISTS process_update (
    id                     VARCHAR(255) NOT NULL PRIMARY KEY,
    process_id             VARCHAR(255) NOT NULL,
    workflow_definition_id VARCHAR(255),
    update_name            VARCHAR(255) NOT NULL,
    idempotency_key        VARCHAR(255) NOT NULL,
    request_hash           VARCHAR(64),
    payload                TEXT,
    status                 VARCHAR(32)  NOT NULL,
    result                 TEXT,
    error                  TEXT,
    requested_by           VARCHAR(255),
    with_start             BOOLEAN      DEFAULT FALSE NOT NULL,
    requested_at           TIMESTAMP,
    completed_at           TIMESTAMP,
    expires_at             TIMESTAMP,
    CONSTRAINT uk_process_update_key UNIQUE (process_id, idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_process_update_process ON process_update (process_id);
CREATE INDEX IF NOT EXISTS idx_process_update_expires ON process_update (expires_at);

-- The definition's `updates`, as the file writes them (JSON); null when it declares none.
ALTER TABLE workflow_definition_entity ADD COLUMN IF NOT EXISTS updates_json TEXT;
