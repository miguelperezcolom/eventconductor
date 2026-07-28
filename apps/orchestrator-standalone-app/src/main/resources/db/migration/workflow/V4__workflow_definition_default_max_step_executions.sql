-- Per-definition default cap on step executions per process (0 = unbounded),
-- overridable per step via maxExecutions.
ALTER TABLE workflow_definition_entity
    ADD COLUMN IF NOT EXISTS default_max_step_executions INTEGER NOT NULL DEFAULT 0;
