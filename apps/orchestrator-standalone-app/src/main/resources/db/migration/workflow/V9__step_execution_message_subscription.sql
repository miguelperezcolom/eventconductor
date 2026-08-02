-- Materialised message subscription: what a live WAIT_FOR_MESSAGE step is waiting for. An
-- arriving message is now an indexed lookup on (name, key) instead of a walk over every step
-- waiting anywhere in the engine — the message name alone was never selective enough, since the
-- interesting case is many processes parked on the same one.
--
-- awaiting_correlation_key is the step's correlationExpression evaluated against the process
-- (businessKey for steps persisted before the rename, which had none). It stays current: every
-- path that updates process variables recomputes it, so correlation still reads the process as
-- it is now rather than as it was at start. Null means "matches nothing", which is how an
-- expression that will not evaluate keeps failing closed.
--
-- Steps already waiting at upgrade time are left null here and armed at the next boot by
-- InFlightStepRearmRunner — SQL cannot evaluate a JEXL expression over a JSON column.

ALTER TABLE step_execution_entity
    ADD COLUMN IF NOT EXISTS awaiting_message_name VARCHAR(255);

ALTER TABLE step_execution_entity
    ADD COLUMN IF NOT EXISTS awaiting_correlation_key VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_step_exec_awaiting_message
    ON step_execution_entity (awaiting_message_name, awaiting_correlation_key);
