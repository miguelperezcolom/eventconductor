-- Child workflows (PROCESS steps): a child process keeps a link back to the parent
-- step execution that spawned it, so that step can be completed (or errored) when
-- the child reaches a terminal status. Null for top-level processes.
ALTER TABLE process_entity
    ADD COLUMN IF NOT EXISTS parent_step_execution_id VARCHAR(255);
