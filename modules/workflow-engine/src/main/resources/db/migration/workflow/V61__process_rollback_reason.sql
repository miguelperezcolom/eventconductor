-- Why the engine rolled a process back from outside: its parent cancelled the PROCESS step that started
-- it while it was still running (the parent rolling back, a forEach failing fast, an abandoned branch with
-- compensateLosers/compensateAbandoned). Null for every other process, and for every row written before.
--
-- Plain portable DDL with IF NOT EXISTS, as V47: it also runs over a schema ddl-auto created from the
-- entity (ProcessEntity).

ALTER TABLE process_entity ADD COLUMN IF NOT EXISTS rollback_reason VARCHAR(255);
