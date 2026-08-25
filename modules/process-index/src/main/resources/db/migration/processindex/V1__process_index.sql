-- The read database's own schema: the process-index read model, and nothing else.
--
-- Deliberately a second migration set rather than a share of the engine's. The engine's
-- V18__process_index.sql stays exactly where it is — an existing deployment's migration history must
-- not change under it — and a read database must not run the write side's migrations to get one
-- table. The same eleven columns in two migration sets is a real (small) duplication; making the read
-- database carry the whole write schema would be the more expensive mistake.
--
-- IF NOT EXISTS throughout, like every other V1 here, so that adopting this over a schema something
-- else already created is a no-op rather than a failure (see ManagedSchema).
CREATE TABLE IF NOT EXISTS process_index (
    process_id                   varchar(255) PRIMARY KEY,
    business_key                 varchar(255),
    workflow_definition_id       varchar(255),
    workflow_definition_version  integer      NOT NULL DEFAULT 0,
    status                       varchar(255),
    completion_percentage        integer      NOT NULL DEFAULT 0,
    created                      timestamp,
    started                      timestamp,
    finished                     timestamp,
    updated_at                   timestamp,
    shard_id                     varchar(255)
);

CREATE INDEX IF NOT EXISTS idx_process_index_status         ON process_index (status);
CREATE INDEX IF NOT EXISTS idx_process_index_def_status     ON process_index (workflow_definition_id, status);
CREATE INDEX IF NOT EXISTS idx_process_index_business_key   ON process_index (business_key);
