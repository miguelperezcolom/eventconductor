-- Synchronous invocation, part 1: the reply a process gives its caller, and the definition-level
-- configuration that makes a definition sync-invocable.
--
-- The reply lives on the process row, not beside it: it is produced by whichever transition answers
-- (a REPLY step, a failure, a finished rollback, a cancellation) and must commit with that
-- transition, and the process row is the one thing every one of those paths already saves under
-- its @Version. Read whole with the process, never queried by field — except replied_at, which the
-- waiting pods poll by primary key.
--
-- Plain portable DDL with IF NOT EXISTS, as V29/V30: it also runs over a schema ddl-auto already
-- created from the entities.
ALTER TABLE process_entity ADD COLUMN IF NOT EXISTS reply_step_id VARCHAR(255);
ALTER TABLE process_entity ADD COLUMN IF NOT EXISTS reply_json TEXT;
ALTER TABLE process_entity ADD COLUMN IF NOT EXISTS reply_outcome VARCHAR(40);
ALTER TABLE process_entity ADD COLUMN IF NOT EXISTS reply_compensation VARCHAR(40);
ALTER TABLE process_entity ADD COLUMN IF NOT EXISTS reply_error TEXT;
ALTER TABLE process_entity ADD COLUMN IF NOT EXISTS replied_at TIMESTAMP;

ALTER TABLE workflow_definition_entity ADD COLUMN IF NOT EXISTS sync_invocation_json TEXT;
