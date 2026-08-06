-- Composite index backing the pending-tasks listing query, which filters on
-- status plus user_id (unassigned or assigned to the current user). Replaces
-- the status-only index, redundant now that the composite covers its prefix.

CREATE INDEX IF NOT EXISTS idx_form_exec_status_user ON form_execution_entity (status, user_id);

DROP INDEX IF EXISTS idx_form_exec_status;
