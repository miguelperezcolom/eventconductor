-- Batch operations (2.30): an operator command — cancel, retry, restart, pause, resume — applied to every
-- process a filter matches, as an entity of its own: created, walked by a background runner a page at a
-- time (one pod at a time, under an advisory lock), throttled, resumable after a restart, cancellable.
--
-- No item table: the runner walks the filter by process id and keeps the last id it reached in
-- cursor_id, which is all a restart needs to carry on, however many processes the filter matches. The
-- filter, parameters, skip reasons, error sample and caller are JSON, read whole with the row.
--
-- The runner asks for "the oldest active batch" (status, created_at); the console lists the newest
-- (created_at).
--
-- Plain portable DDL with IF NOT EXISTS, as V39-V48: it also runs over a schema ddl-auto created from
-- the entity (BatchOperationEntity).

CREATE TABLE IF NOT EXISTS batch_operation (
    id                      VARCHAR(64)   NOT NULL PRIMARY KEY,
    op_type                 VARCHAR(32)   NOT NULL,
    status                  VARCHAR(16)   NOT NULL,
    filter_json             TEXT,
    parameters_json         TEXT,
    matched                 BIGINT        NOT NULL DEFAULT 0,
    succeeded               BIGINT        NOT NULL DEFAULT 0,
    failed                  BIGINT        NOT NULL DEFAULT 0,
    skipped                 BIGINT        NOT NULL DEFAULT 0,
    skipped_by_reason_json  TEXT,
    cursor_id               VARCHAR(255),
    errors_json             TEXT,
    created_by              VARCHAR(255),
    caller_json             TEXT,
    created_at              TIMESTAMP,
    started_at              TIMESTAMP,
    finished_at             TIMESTAMP,
    updated_at              TIMESTAMP,
    failure                 VARCHAR(1000)
);

CREATE INDEX IF NOT EXISTS idx_batch_operation_status_created ON batch_operation (status, created_at);
CREATE INDEX IF NOT EXISTS idx_batch_operation_created        ON batch_operation (created_at);
