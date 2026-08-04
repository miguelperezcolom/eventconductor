-- Append-only history of workflow-definition versions.
--
-- The definition head row (workflow_definition_entity) is still one row per id, overwritten on save.
-- This table keeps the immutable history: one row per engine-recorded version, with the moment it was
-- recorded and a frozen JSON snapshot so a version's diagram can be rendered exactly as it was, no
-- matter how the head later changes. A new row is written only when the definition's content changes
-- (see WorkflowDefinitionVersioningService); lifecycle toggles and unchanged re-imports record nothing.
--
-- IF NOT EXISTS throughout so this co-exists with a schema ddl-auto already created (same contract as
-- V12/V13). The primary key "<definitionId>:<version>" makes a concurrent record of the same version
-- fail rather than duplicate.
CREATE TABLE IF NOT EXISTS workflow_definition_version (
    id             VARCHAR(255) PRIMARY KEY,
    definition_id  VARCHAR(255) NOT NULL,
    version        INTEGER      NOT NULL,
    name           VARCHAR(255),
    snapshot_json  TEXT,
    content_hash   VARCHAR(64)  NOT NULL,
    created_at     TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_wf_def_version_def
    ON workflow_definition_version (definition_id, version);

-- Per-version process stats read (definition_id, workflow_definition_version); the baseline only
-- indexed definition_id. This composite serves both the count-by-version and the per-version list.
CREATE INDEX IF NOT EXISTS idx_process_wf_def_version
    ON process_entity (workflow_definition_id, workflow_definition_version);
