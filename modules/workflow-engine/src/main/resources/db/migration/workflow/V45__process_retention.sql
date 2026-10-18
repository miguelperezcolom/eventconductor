-- Process retention: finished processes are deleted once their definition's `retention` (or the
-- engine-wide workflow.processes.retention* default) has passed since they ended.
--
-- The definition's `retention` rides on its row as JSON — a string ("P30D") or an object per terminal
-- status — like its tags. Metadata: not part of the version hash.
ALTER TABLE workflow_definition_entity ADD COLUMN IF NOT EXISTS retention_json TEXT;

-- The purge asks, per definition and terminal status, "which processes ended before the cutoff?",
-- oldest first: two equalities, then the finished range — one short range scan per (definition,
-- status) however large the table is. It replaces V1's (workflow_definition_id), which it covers as
-- a prefix, so process_entity keeps the same number of indexes to maintain on its hot path.
CREATE INDEX IF NOT EXISTS idx_process_retention
    ON process_entity (workflow_definition_id, status, finished);

DROP INDEX IF EXISTS idx_process_wf_def;

-- A process tree is purged together: the children of a process are the processes whose parent step
-- execution belongs to it. Without this, finding them is a sequential scan of process_entity per
-- purged process.
CREATE INDEX IF NOT EXISTS idx_process_parent_step
    ON process_entity (parent_step_execution_id);

-- A purged process's resources go with it; the delete is by process id.
CREATE INDEX IF NOT EXISTS idx_resource_process
    ON resource_entity (process_id);

-- Plain portable DDL with IF NOT EXISTS, as V29-V44: it also runs over a schema ddl-auto created
-- from the entities (ProcessEntity, ResourceEntity and WorkflowDefinitionEntity declare the same).
