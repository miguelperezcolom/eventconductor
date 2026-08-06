-- The timer and timeout checks look up the live (PENDING/RUNNING) steps of one process, so
-- the query filters on process_id plus status. Replaces the process-only index, redundant
-- now that the composite covers its prefix. The status-only index stays: the scheduler scan
-- still lists every live step across all processes.

CREATE INDEX IF NOT EXISTS idx_step_exec_process_status ON step_execution_entity (process_id, status);

DROP INDEX IF EXISTS idx_step_exec_process;
