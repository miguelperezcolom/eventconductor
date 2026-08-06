-- CQRS process-index read model. Written only by the projector (when workflow.projection.enabled),
-- read by every process listing / lookup / count so those queries never scan the write tables.
-- Created unconditionally (empty and cheap when the projector is off) so enabling the read model is
-- a config flip, not a migration.
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
