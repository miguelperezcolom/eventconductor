-- Materialised deadline: the moment a live step needs the engine's attention — a TIMER's due
-- moment or a step's timeout. It derives from started_at, the step's variables and its JSON,
-- all frozen when the step starts, so the scheduler no longer has to evaluate every live step
-- on every tick: it asks for deadline_at <= now over this index and normally gets nothing back.
--
-- Null means "no deadline": no timeout configured, not started, or a TIMER whose date could not
-- be resolved (which fails the step at start instead). Steps already in flight at upgrade time
-- are left null here and armed at boot by StepDeadlineBackfillRunner, which recomputes them
-- from the state they already carry — SQL cannot, since the TIMER case reads a process variable
-- out of a JSON column.

ALTER TABLE step_execution_entity
    ADD COLUMN IF NOT EXISTS deadline_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_step_exec_deadline ON step_execution_entity (deadline_at);
