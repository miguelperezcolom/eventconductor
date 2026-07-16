-- Process analytics: per-step durations need the moment a step reached a
-- terminal status (COMPLETED, CANCELLED, ERROR, TIMEOUT), not just when it started.
ALTER TABLE step_execution_entity
    ADD COLUMN IF NOT EXISTS finished_at TIMESTAMP;
