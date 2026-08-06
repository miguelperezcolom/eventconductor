-- Pause/resume: a paused process (status PAUSED — stored in the existing status string
-- column, no migration needed for the enum value) records when it was paused so that, on
-- resume, in-flight step clocks (started_at) can be shifted by the pause duration, freezing
-- TIMER due moments and step timeouts. Null while not paused.
ALTER TABLE process_entity
    ADD COLUMN IF NOT EXISTS paused_at TIMESTAMP;

-- Runtime pause flag on the definition: while true all its processes are held and new
-- instances are created already paused. Orthogonal to the lifecycle status.
ALTER TABLE workflow_definition_entity
    ADD COLUMN IF NOT EXISTS paused BOOLEAN NOT NULL DEFAULT FALSE;
